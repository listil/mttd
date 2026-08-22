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

    /**
     * ETOR 시즌 선택([SeasonMode.REGULAR]/[SeasonMode.HARDCORE]) — 항상 수동이다. 로그에서
     * 시즌ID 를 읽어 자동 전환하는 걸 시도한 적이 있는데(`SessionAggregator.onSeasonIdDetected`,
     * `LogPoller.findRecentSeasonId`), 역방향 스캔과 실시간 감지 사이 순서 보장이 없어 재접속
     * 타이밍에 따라 최신 감지값이 스캔의 stale 결과로 덮어써지는 등 신뢰성 문제가 있어 걷어냈다
     * (2026-08-22) — 다시 붙이려면 그 레이스부터 해결해야 한다. 변경은 [setSeasonMode] 로만.
     * 앱 시작 시 저장된 값을 [restoreSeasonMode] 로 주입.
     */
    private val _seasonMode = MutableStateFlow(SeasonMode.REGULAR)
    val seasonMode: StateFlow<SeasonMode> = _seasonMode.asStateFlow()

    /** 저장된 설정 복원. 아직 fetch 하지 않는다. */
    fun restoreSeasonMode(m: SeasonMode) {
        _seasonMode.value = m
    }

    /** 모드 전환 + 즉시 재조회 (ETOR 를 보고 있을 때만 의미 있음). */
    suspend fun setSeasonMode(m: SeasonMode): Result<Int> {
        if (_seasonMode.value == m) return Result.success(_state.value.priceById.size)
        _seasonMode.value = m
        return refreshLatest(forceRefresh = true)
    }

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
     * 최신 시즌 fetch. ETOR 는 [_seasonMode] 가 가리키는 체인(정규/하드코어)에서 스윕.
     *
     * 시즌 ID 는 CN 서버 규칙상 100 단위로 증가 (S12=1401, S13=1501, S14=1601...) — 정규/
     * 하드코어는 완전히 별개 체인이라([HARDCORE_SEASON_OFFSET]) 어느 체인인지는 유저가
     * [setSeasonMode] 로 직접 고른다. 각 체인 안에서는 최신→과거 순서로 시도해 첫 유효(가격
     * 데이터 있는) 응답을 쓴다.
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
            // 캐시된 이전 시즌ID 를 스윕 시작점으로 재사용하면 반대 체인(정규↔하드코어) 값에서
            // 출발해 엉뚱한 시즌을 찾을 수 있으므로, 항상 그 체인의 고정 시작점에서 새로 스윕한다.
            PriceSource.ETOR -> when (_seasonMode.value) {
                SeasonMode.REGULAR -> sweepSeasonChain(BASE_SEASON)
                SeasonMode.HARDCORE -> sweepSeasonChain(BASE_SEASON + HARDCORE_SEASON_OFFSET)
            }
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
     * [start] 부터(그 체인의 고정 시작점 — [BASE_SEASON] 또는 `+`[HARDCORE_SEASON_OFFSET]) 위로
     * 탐색해 **가장 높은 유효 시즌**(과거 시즌은 안 씀)을 찾는다. 시즌 ID 는 `<Sxx*100>+1`
     * 규칙으로 100 씩 증가하므로([SEASON_STEP]) 하드코딩 후보 리스트 대신 위로 탐색해서 새
     * 시즌이 열려도 코드 수정 없이 자동으로 따라간다. 정상 상태에서 네트워크 호출은 2 회
     * (현재 시즌 OK → 다음 시즌 empty → 중단).
     */
    private suspend fun sweepSeasonChain(start: Int): Result<Int> {
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
        Log.i(TAG, "using season $season (start=$start)")
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
        /**
         * 하드코어 시즌ID 는 같은 세대 정규 시즌ID 에서 정확히 이만큼 높다(정규 1501 ↔
         * 하드코어 1531, 정규 1401 ↔ 하드코어 1431 — 2026-08-22 실측/게임 내 확인). 하드코어도
         * 세대마다 [SEASON_STEP] 씩 증가하는 별개 체인이라 [SeasonMode.HARDCORE] 스윕은
         * `BASE_SEASON + HARDCORE_SEASON_OFFSET` 에서 시작한다.
         */
        private const val HARDCORE_SEASON_OFFSET = 30
        private const val SEASON_STEP = 100
        /** 탐색 하한/상한 — 무한 루프 방지용 가드. */
        private const val MIN_SEASON = 1001
        private const val MAX_SEASON = 3001
    }
}
