package com.mttd.data.prices

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory 시세 캐시 + 갱신 정책.
 *
 * - hot path: `Map<String, Float>` (itemId → 가격) 은 in-memory 만.
 * - 갱신: 명시적 [refresh] 호출 또는 [ensureFresh] (TTL 초과 시 자동).
 * - TTL: 1시간 (ETor 데스크톱과 동일).
 * - 앱 재시작 시 캐시는 사라짐 — 다음 요청 시 다시 fetch (Room 영속화는 phase 2).
 */
class PriceRepository(
    private val api: PriceApi,
    private val ttdApi: TtdPriceApi = TtdPriceApi(),
    /**
     * TTD 응답의 name/type 은 가격 계산엔 안 쓰지만 [refreshTtd] 가 어차피 fetch 하는
     * 부산물이라, 새 아이템 이름 사전(gap-fill) 을 채우고 싶은 쪽(ItemInfoLookup)에
     * 넘겨준다. TTD 가 실제로 fetch 될 때만 호출됨 — 이걸 위해 별도 네트워크 호출은 없음.
     */
    private val onTtdItemsFetched: (Map<String, TtdPriceApi.Item>) -> Unit = {},
) {

    private val fetchLock = Mutex()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 어느 소스를 쓸지. 변경은 [switchSource] 로만 (전환 즉시 재조회해야 하므로).
     * 앱 시작 시 저장된 값을 [restoreSource] 로 주입.
     */
    private val _source = MutableStateFlow(PriceSource.ETOR)
    val source: StateFlow<PriceSource> = _source.asStateFlow()

    /** 저장된 설정 복원. 아직 fetch 하지 않는다. */
    fun restoreSource(s: PriceSource) {
        _source.value = s
    }

    /** 소스 전환 + 즉시 재조회. 이전 소스의 가격 맵은 버린다. */
    suspend fun switchSource(s: PriceSource): Result<Int> {
        if (_source.value == s && _state.value.itemsWithPrice > 1) return Result.success(0)
        _source.value = s
        _state.value = State(source = s)   // 이전 소스 값이 섞이지 않게 초기화
        return refreshLatest(forceRefresh = true)
    }

    /**
     * 최신 시즌 자동 감지 후 fetch.
     *
     * 시즌 ID 는 CN 서버 규칙상 100 단위로 증가 (S12=1401, S13=1501, S14=1601...).
     * 후보 리스트를 최신→과거 순서로 시도하고 첫 유효(가격 데이터 있는) 응답을 사용.
     * 마지막에 성공한 시즌 ID 는 인메모리 캐시에 남아 다음 요청 시 우선 시도.
     */
    suspend fun refreshLatest(forceRefresh: Boolean = false): Result<Int> {
        val cur = _state.value
        val now = System.currentTimeMillis()
        val fresh = cur.seasonId != null &&
                    cur.source == _source.value &&
                    cur.lastUpdatedMs > 0 &&
                    (now - cur.lastUpdatedMs) < TTL_MS &&
                    cur.totalItems > 1 && cur.itemsWithPrice > 1
        if (!forceRefresh && fresh) return Result.success(cur.priceById.size)

        return when (_source.value) {
            PriceSource.TTD -> refreshTtd()
            PriceSource.ETOR -> refreshEtorLatestSeason()
        }
    }

    /** TTD 는 시즌 개념이 없어 단일 URL 한 번만 받는다. */
    private suspend fun refreshTtd(): Result<Int> = fetchLock.withLock {
        _state.value = _state.value.copy(loading = true, lastError = null)
        try {
            val items = ttdApi.fetchPrices()
            onTtdItemsFetched(items)
            val map = HashMap<String, Float>(items.size)
            for ((id, item) in items) map[id] = item.price
            // 가치 기준 단위는 항상 최초의 불꽃 결정 = 1.
            map["100300"] = 1.0f
            _state.value = State(
                source = PriceSource.TTD,
                seasonId = TTD_SEASON_LABEL,
                priceById = map,
                totalItems = map.size,
                itemsWithPrice = map.values.count { it > 0f },
                mode = "ttd",
                lastUpdatedMs = System.currentTimeMillis(),
                loading = false,
            )
            Log.i(TAG, "loaded ${map.size} TTD prices (${_state.value.itemsWithPrice} priced)")
            Result.success(map.size)
        } catch (t: Throwable) {
            Log.w(TAG, "TTD refresh failed", t)
            _state.value = _state.value.copy(loading = false, lastError = t.message ?: t::class.simpleName)
            Result.failure(t)
        }
    }

    /**
     * **최신 시즌만** 유지한다 (과거 시즌은 조회하지 않음).
     *
     * 시즌 ID 는 `<Sxx*100>+1` 규칙으로 100 씩 증가한다 (S13=1501, S14=1601 …).
     * 하드코딩 후보 리스트 대신 **위로 탐색**해서 가장 높은 유효 시즌을 찾는다.
     * 새 시즌이 열려도 코드 수정 없이 자동으로 따라간다.
     *
     * 정상 상태에서 네트워크 호출은 2 회 (현재 시즌 OK → 다음 시즌 empty → 중단).
     */
    private suspend fun refreshEtorLatestSeason(): Result<Int> {
        val start = _state.value.seasonId?.toIntOrNull()
            ?.takeIf { _state.value.source == PriceSource.ETOR }
            ?: BASE_SEASON

        var bestSeason: Int? = null
        var bestCount = 0
        var lastError: Throwable? = null

        // 1) start 부터 위로 — 유효한 동안 계속 올린다.
        var s = start
        while (s <= MAX_SEASON) {
            val r = probe(s)
            if (r == null) break
            bestSeason = s; bestCount = r
            s += SEASON_STEP
        }

        // 2) start 자체가 이미 종료된 시즌이면 아래로 내려가며 찾는다.
        if (bestSeason == null) {
            s = start - SEASON_STEP
            while (s >= MIN_SEASON) {
                val r = probe(s)
                if (r != null) { bestSeason = s; bestCount = r; break }
                s -= SEASON_STEP
            }
        }

        val season = bestSeason
            ?: return Result.failure(lastError ?: RuntimeException("no valid season found"))

        // 위로 탐색하다가 마지막에 빈 시즌을 만나면 state 가 그 실패로 덮여 있을 수 있다.
        // 최종 선택 시즌으로 한 번 더 확정 로드.
        if (_state.value.seasonId != season.toString()) refresh(season.toString())
        Log.i(TAG, "using latest season $season")
        return Result.success(bestCount)
    }

    /** 해당 시즌이 유효하면 아이템 수, 아니면 null. */
    private suspend fun probe(seasonId: Int): Int? {
        val r = refresh(seasonId.toString())
        val ok = r.isSuccess && _state.value.totalItems > 1 && _state.value.itemsWithPrice > 1
        return if (ok) r.getOrNull() else null
    }

    /** 특정 시즌 데이터가 최신이면 아무것도 안 함, 아니면 fetch. */
    suspend fun ensureFresh(seasonId: String, forceRefresh: Boolean = false) {
        val cur = _state.value
        val now = System.currentTimeMillis()
        val fresh = cur.seasonId == seasonId &&
                    cur.lastUpdatedMs > 0 &&
                    (now - cur.lastUpdatedMs) < TTL_MS
        if (!forceRefresh && fresh) return
        refresh(seasonId)
    }

    suspend fun refresh(seasonId: String): Result<Int> = fetchLock.withLock {
        _state.value = _state.value.copy(loading = true, lastError = null)
        try {
            val resp = api.fetchSnapshot(seasonId)

            // 서버는 **존재하지 않는 시즌에도 HTTP 200** 을 준다 (seasonId/mode/version 만 담긴 40 B 응답).
            // 이걸 성공으로 처리하면 아래 100300 override 때문에 itemsWithPrice=1 이 되어
            // refreshLatest 가 "가격 있음" 으로 오판하고 실제 시즌(1501)까지 내려가지 않는다.
            // → 가격 맵에 최초의 불꽃 결정 하나만 남고 나머지 아이템 가치가 전부 0 이 된다.
            if (resp.itemsCount == 0) {
                Log.i(TAG, "season $seasonId is empty (${resp.totalCount} items) — skipping")
                _state.value = _state.value.copy(loading = false)
                return@withLock Result.failure(IllegalStateException("season $seasonId has no items"))
            }

            val map = HashMap<String, Float>(resp.itemsCount)
            for (i in 0 until resp.itemsCount) {
                val it = resp.getItems(i)
                map[it.id] = it.price
            }
            // 최초의 불꽃 결정 (기본 통화) — 서버는 자기 자신을 0 으로 리턴하지만
            // 우리 앱은 이걸 가치 기준 단위로 쓰므로 1.0 으로 override.
            map["100300"] = 1.0f
            _state.value = State(
                source = PriceSource.ETOR,
                seasonId = seasonId,
                priceById = map,
                // 100300 override 로 응답에 없던 항목이 하나 늘 수 있으므로 map 기준으로 센다.
                // (resp.itemsCount 를 쓰면 "1674 (가격 있음 1675)" 처럼 역전돼 보인다)
                totalItems = map.size,
                itemsWithPrice = map.values.count { it > 0f },
                version = resp.version,
                mode = resp.mode,
                lastUpdatedMs = System.currentTimeMillis(),
                loading = false,
                lastError = null,
            )
            Log.i(TAG, "loaded ${map.size} prices for season $seasonId (${map.count { it.value > 0f }} priced)")
            Result.success(map.size)
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed", t)
            _state.value = _state.value.copy(loading = false, lastError = t.message ?: t::class.simpleName)
            Result.failure(t)
        }
    }

    fun priceOf(itemId: String?): Float? =
        itemId?.let { _state.value.priceById[it]?.takeIf { p -> p > 0f } }

    data class State(
        val source: PriceSource = PriceSource.ETOR,
        /** ETor 이면 시즌 ID, TTD 이면 [TTD_SEASON_LABEL]. */
        val seasonId: String? = null,
        val priceById: Map<String, Float> = emptyMap(),
        val totalItems: Int = 0,
        val itemsWithPrice: Int = 0,
        val version: String = "",
        val mode: String = "",
        val lastUpdatedMs: Long = 0,
        val loading: Boolean = false,
        val lastError: String? = null,
    )

    companion object {
        private const val TAG = "mTTD.Prices"
        private const val TTL_MS = 60L * 60 * 1000  // 1h
        /** TTD 는 시즌이 없어서 UI 표기용 라벨을 seasonId 자리에 넣는다. */
        const val TTD_SEASON_LABEL = "TTD"

        /**
         * 시즌 ID 규칙: 4자리 `<Sxx*100>+1`, 시즌마다 100 증가.
         * S12=1401, S13=1501 (2026-08 확인), S14=1601 …
         *
         * 과거 시즌은 쓰지 않으므로 항상 **가장 높은 유효 시즌** 하나만 유지한다.
         * 탐색 시작점일 뿐이라 새 시즌이 열려도 수정할 필요 없다.
         */
        private const val BASE_SEASON = 1501
        private const val SEASON_STEP = 100
        /** 탐색 하한/상한 — 무한 루프 방지용 가드. */
        private const val MIN_SEASON = 1001
        private const val MAX_SEASON = 3001
    }
}
