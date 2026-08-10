package com.mttd.data.runs

import android.content.Context
import android.util.Log
import com.mttd.data.items.ItemInfoLookup
import com.mttd.domain.models.MapRun
import com.mttd.domain.models.PickupSummary
import kotlinx.serialization.json.Json

/**
 * 회차 저장/조회 파사드. Room 세부사항을 도메인 밖으로 숨긴다.
 *
 * 메모리 정책:
 * - 완료된 회차의 **아이템 목록은 여기(디스크)에만** 있다.
 * - 메모리에는 [MapRun] 요약(아이템 목록 비어 있음)만 남는다.
 * - 상세 팝업을 열 때만 [loadItems] 로 읽어온다.
 */
class RunRepository(context: Context, private val itemInfo: ItemInfoLookup) {

    private val dao = RunDatabase.get(context).runDao()
    // encodeDefaults = true : 저장 포맷이 기본값에 의존하지 않게 한다.
    // false 로 두면 quantity=1 처럼 "기본값과 같은 값"이 JSON 에서 통째로 빠져서,
    // 나중에 기본값을 바꾸면 예전 레코드가 조용히 다른 값으로 복원된다.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun save(run: MapRun) {
        try {
            dao.insert(
                RunEntity(
                    id = run.id,
                    startedAtMs = run.startedAtMs,
                    endedAtMs = run.endedAtMs,
                    mapName = run.mapName,
                    totalValue = run.totalValue,
                    netTotalValue = run.netTotalValue,
                    pickupCount = run.pickupCount,
                    itemCount = run.items.size,
                    itemsJson = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(PickupSummary.serializer()),
                        run.items,
                    ),
                )
            )
            dao.trimTo(MAX_STORED_RUNS)
        } catch (t: Throwable) {
            Log.w(TAG, "save run ${run.id} failed", t)
        }
    }

    /** 상세 팝업용 — 그때만 아이템 JSON 을 읽어 역직렬화한다. */
    suspend fun loadItems(runId: Long): List<PickupSummary> = try {
        dao.findById(runId)?.itemsJson
            ?.let { json.decodeFromString<List<PickupSummary>>(it) }
            ?.map(::refreshInfo)
            ?: emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "loadItems $runId failed", t)
        emptyList()
    }

    /**
     * 저장 당시의 이름/타입/아이콘 대신 **현재** [ItemInfoLookup] 사전으로 다시 채운다.
     * 픽업 시점엔 사전에 없던 아이템(신규 시즌 등)도 나중에 데이터가 채워지면
     * 과거 기록에도 소급 반영되게 하려는 것 — 수량/가격/가치는 그날 기록 그대로 둔다.
     * 사전에 여전히 없으면(=lookup 이 null) 저장된 값을 그대로 유지.
     */
    private fun refreshInfo(p: PickupSummary): PickupSummary {
        val info = p.itemId?.let(itemInfo::lookup) ?: return p
        return p.copy(
            itemName = info.name.takeIf { it.isNotBlank() } ?: p.itemName,
            itemType = info.type.takeIf { it.isNotBlank() } ?: p.itemType,
            iconUrl = info.img.takeIf { it.isNotBlank() } ?: p.iconUrl,
        )
    }

    /** 앱/서비스 재시작 후 그래프 복원용 요약. 아이템 목록은 비어 있다. */
    suspend fun loadSummaries(): List<MapRun> = try {
        dao.summaries(MAX_STORED_RUNS).map {
            MapRun(
                id = it.id,
                index = 0,
                startedAtMs = it.startedAtMs,
                endedAtMs = it.endedAtMs,
                mapName = it.mapName,
                items = emptyList(),
                storedTotalValue = it.totalValue,
                storedNetTotalValue = it.netTotalValue,
                storedPickupCount = it.pickupCount,
                storedItemCount = it.itemCount,
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "loadSummaries failed", t)
        emptyList()
    }

    /**
     * 저장된 모든 회차의 아이템을 itemId 기준으로 합산.
     * 세션 전체 아이템 합계를 복원할 때 **시작 시 한 번만** 쓴다.
     */
    suspend fun loadMergedItems(): List<PickupSummary> = try {
        val acc = LinkedHashMap<String, PickupSummary>()
        for (row in dao.summaries(MAX_STORED_RUNS)) {
            for (p in loadItems(row.id)) {
                val id = p.itemId ?: continue
                val prev = acc[id]
                acc[id] = if (prev == null) p
                          else prev.copy(
                              quantity = prev.quantity + p.quantity,
                              value = prev.value + p.value,
                              netValue = prev.netValue + p.netValue,
                              unitPrice = p.unitPrice.takeIf { it > 0f } ?: prev.unitPrice,
                          )
            }
        }
        acc.values.toList()
    } catch (t: Throwable) {
        Log.w(TAG, "loadMergedItems failed", t)
        emptyList()
    }

    suspend fun delete(runId: Long) {
        try { dao.delete(runId) } catch (t: Throwable) { Log.w(TAG, "delete $runId failed", t) }
    }

    suspend fun clear() {
        try { dao.clear() } catch (t: Throwable) { Log.w(TAG, "clear failed", t) }
    }

    companion object {
        private const val TAG = "mTTD.Runs"
        /** 디스크 보관 상한. 넘으면 오래된 회차부터 지운다. */
        const val MAX_STORED_RUNS = 500
    }
}
