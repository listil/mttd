package com.mttd.domain.models

import kotlinx.serialization.Serializable

/**
 * 세션 스냅샷. UI 가 collect 해서 렌더링.
 */
data class SessionState(
    val active: Boolean = false,
    val startedAtMs: Long = 0,
    val endedAtMs: Long? = null,

    val currentMapId: String? = null,
    val currentAreaId: String? = null,
    val currentMapName: String? = null,

    val mapsEntered: Int = 0,
    val mapsCompleted: Int = 0,
    /** 마지막으로 맵 진입 카운트 증가한 시각 (dedupe 용 내부 필드). */
    val lastEnterAreaMs: Long = 0,

    val pickupCount: Int = 0,
    /** 이번 맵의 아이템별 누적. |가치| 내림차순, 최대 [SessionAggregator.MAX_RECENT_PICKUPS] 개. */
    val recentPickups: List<PickupSummary> = emptyList(),
    /**
     * 이번 맵에서의 수익 합계 (획득 − 소비).
     * [recentPickups] 는 상위 N 개로 잘리므로 그걸 더하면 안 되고, 전체 합을 따로 들고 간다.
     */
    val currentMapValue: Double = 0.0,

    /**
     * 회차별 기록. 마지막 항목이 진행 중인 회차.
     * 세션 총합([totalValue]/[pickupCount])은 이 목록에서 파생되므로,
     * 회차를 지우면 총합도 같이 줄어든다.
     */
    val runs: List<MapRun> = emptyList(),

    /**
     * 세션 전체 아이템 합계 (모든 회차 누적). |가치| 내림차순.
     *
     * 회차마다 다시 훑으면 픽업 1 건마다 O(회차 × 아이템) 이 되므로 증분으로 유지한다.
     */
    val sessionItems: List<PickupSummary> = emptyList(),

    /** 누적 수익 (게임 내 통화 "fire" 기준 float, 시장가). */
    val totalValue: Double = 0.0,
    /** 경매장 세금(1/8) 반영한 실수령 예상 누적 수익. [totalValue] 와 별개로 회차에서 파생. */
    val netTotalValue: Double = 0.0,
    /** 아직 가격 미상인 픽업 개수 (참고용). */
    val unpricedPickups: Int = 0,

    /**
     * 가방 전체 스냅샷(가방 정렬 / 마을 귀환 / 재접속) 을 관측했는지.
     *
     * 스냅샷 전에는 슬롯별 기준 수량을 모르므로 픽업 계산을 시작하지 않는다.
     * false 이면 UI 에 "가방 정렬 대기 중" 안내를 띄운다.
     */
    val baselineReady: Boolean = false,

    /** 사용자 일시정지 상태. true 이면 경과 시간·픽업·수익 계산 일시 중단. */
    val paused: Boolean = false,
    /** 지금까지 누적된 일시정지 시간 (ms). elapsedMs 계산에서 빼줌. */
    val pausedAccumulatedMs: Long = 0,
    /** 현재 pause 구간 시작 시각. paused=true 일 때만 non-null. */
    val pausedSinceMs: Long? = null,

    /** 1분당 1점, 최대 60개 슬라이딩. 누적 [totalValue] 시계열. */
    val valueSeries: List<TimeSample> = emptyList(),

    /** 게임 내 거래소(경매장) 화면이 열려있는 동안 true. 열려있는 동안은 픽업 집계를 멈춘다. */
    val inExchange: Boolean = false,
    /** 거래소 진입 시점(또는 수동 새로고침 시점)의 현재 보유 아이템, 가치 내림차순. */
    val holdings: List<PickupSummary> = emptyList(),
) {
    /**
     * 경과 시간. baseline (가방 정렬) 관측 전에는 0.
     * paused 구간의 시간은 누적 제외.
     */
    val elapsedMs: Long
        get() {
            if (!active || !baselineReady) return 0
            val now = System.currentTimeMillis()
            val raw = (endedAtMs ?: now) - startedAtMs
            val currentPauseChunk =
                if (paused && pausedSinceMs != null) now - pausedSinceMs else 0
            return (raw - pausedAccumulatedMs - currentPauseChunk).coerceAtLeast(0)
        }

    /**
     * 시간당 수익. 매 접근 시 재계산되므로 UI 가 tick 마다 collect 하면 실시간으로 갱신됨.
     * 세션 초반(경과 <5s)에는 노이즈 회피를 위해 0 반환.
     */
    val incomePerHour: Double
        get() {
            val e = elapsedMs
            return if (e < 5000) 0.0 else totalValue * 3_600_000.0 / e
        }

    /** [incomePerHour] 의 세후(실수령) 버전. [netTotalValue] 기준. */
    val netIncomePerHour: Double
        get() {
            val e = elapsedMs
            return if (e < 5000) 0.0 else netTotalValue * 3_600_000.0 / e
        }
}

data class TimeSample(val timeSlotMs: Long, val cumulativeValue: Double)

/**
 * 맵 1회차 기록.
 *
 * 맵 열기(`Spv3Open`)로 시작하고, 다음 맵 열기 또는 맵에서 나가는 신호(`AudioBGM OnExit
 * Level`, 둘 중 먼저 오는 쪽)로 끝난다 — 나간 뒤 다음 맵을 바로 안 열어도 경과시간이 계속
 * 늘어나지 않도록. 마지막 항목이 진행 중인 회차 ([endedAtMs] == null).
 * 잘못 집계된 회차는 [SessionState.runs] 에서 통째로 지울 수 있고,
 * 지우면 세션 총합도 다시 계산된다.
 */
data class MapRun(
    /** 생성 순서로 부여되는 고유 ID. 삭제/선택 키로 사용. */
    val id: Long,
    /** 사용자에게 보여줄 회차 번호 (1부터). 삭제해도 남은 것끼리 다시 매긴다. */
    val index: Int,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val mapName: String? = null,
    /**
     * 이 회차의 아이템별 누적. |가치| 내림차순.
     *
     * **완료된 회차는 비어 있다.** 아이템 목록은 Room 으로 내려가고 메모리에는 요약만 남는다
     * (장시간 세션에서 프로세스 메모리가 계속 늘지 않도록).
     * 상세 팝업을 열 때 `RunRepository.loadItems` 로 읽는다.
     */
    val items: List<PickupSummary> = emptyList(),

    // 아이템 목록이 디스크로 내려간 뒤에도 그래프/합계를 그릴 수 있게 들고 있는 요약값.
    private val storedTotalValue: Double? = null,
    private val storedPickupCount: Int? = null,
    private val storedItemCount: Int? = null,
    private val storedNetTotalValue: Double? = null,
) {
    val totalValue: Double get() = storedTotalValue ?: items.sumOf { it.value }
    /** 경매장 세금(1/8) 반영한 이 회차의 실수령 예상 합계. */
    val netTotalValue: Double get() = storedNetTotalValue ?: items.sumOf { it.netValue }
    /** 양수 획득만 (소비 제외). */
    val pickupCount: Int get() = storedPickupCount ?: items.count { it.quantity > 0 }
    /** 획득한 아이템 종류 수. */
    val itemCount: Int get() = storedItemCount ?: items.size
    val inProgress: Boolean get() = endedAtMs == null
    val durationMs: Long get() = (endedAtMs ?: System.currentTimeMillis()) - startedAtMs

    /** 메모리에서 아이템 목록을 떼고 요약만 남긴다 (디스크 저장 직후). */
    fun toSummary(): MapRun = copy(
        items = emptyList(),
        storedTotalValue = totalValue,
        storedPickupCount = pickupCount,
        storedItemCount = itemCount,
        storedNetTotalValue = netTotalValue,
    )
}

@Serializable
data class PickupSummary(
    val timestampMs: Long,
    val action: String,
    val itemId: String? = null,
    val itemName: String? = null,
    val itemType: String? = null,
    /** CDN 아이콘 URL (cdn.tlidb.com). item_names_ko.json 의 img 필드에서 옴. */
    val iconUrl: String? = null,
    val quantity: Int = 1,
    val unitPrice: Float = 0f,
    val value: Double = 0.0,       // = quantity * unitPrice (시장가)
    /** 경매장 세금(1/8) 반영한 실수령 예상치. 소비(quantity<0)는 세금 미적용, [value] 와 동일. */
    val netValue: Double = 0.0,
    val rawSummary: String = "",
)
