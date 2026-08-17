package com.mttd.domain

import com.mttd.data.items.ItemInfoLookup
import com.mttd.data.log.HeaderKind
import com.mttd.data.log.MessageAssembler
import com.mttd.data.maps.MapNameLookup
import com.mttd.domain.models.MapRun
import com.mttd.domain.models.PickupSummary
import com.mttd.domain.models.SessionState
import com.mttd.domain.models.TimeSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 게임 이벤트 스트림을 [SessionState] 로 집계.
 *
 * 세션 시작/종료 를 명시적으로 제어 (수동 시작/종료 버튼).
 * 세션이 비활성일 때 들어오는 이벤트는 UI 표시용으로 흘리기만 하고 카운터엔 반영 안 함.
 */
class SessionAggregator(
    private val itemInfo: ItemInfoLookup,
    private val valueCalculator: ValueCalculator? = null,
    private val observedPrices: com.mttd.data.prices.ObservedPriceStore? = null,
    private val mapNames: MapNameLookup? = null,
    /** 완료된 회차를 디스크로 내리는 콜백. null 이면 메모리에만 둔다 (테스트용). */
    private val onRunFinished: ((MapRun) -> Unit)? = null,
) {
    // 항상 active. ETor 데스크톱과 동일한 UX (명시적 세션 시작/종료 없음).
    // baselineReady = false 로 시작 → 가방 스냅샷(정렬/귀환/재접속) 관측 후 자동 true.
    private val _state = MutableStateFlow(
        SessionState(
            active = true,
            startedAtMs = System.currentTimeMillis(),
            baselineReady = false,
        )
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun resetSession() {
        _state.value = SessionState(
            active = true,
            startedAtMs = System.currentTimeMillis(),
            baselineReady = false,   // 다시 가방 정렬 관측할 때까지 계산 대기
        )
        slotLastCount.clear()
        slotKeyByPosition.clear()
        pendingSlot = null
        latestMapCode = null
        currentRunByItem.clear()
        finishedRuns.clear()
        sessionItemTotals.clear()
        currentRunId = -1
        currentRunMapName = null
        currentRunIsMapRun = false
        nextRunId = 1
        pausedByExchange = false
    }

    fun pauseSession() {
        _state.update {
            if (it.paused) it
            else it.copy(paused = true, pausedSinceMs = System.currentTimeMillis())
        }
    }

    fun resumeSession() {
        _state.update {
            if (!it.paused) return@update it
            val since = it.pausedSinceMs ?: return@update it
            val chunk = System.currentTimeMillis() - since
            it.copy(
                paused = false,
                pausedSinceMs = null,
                pausedAccumulatedMs = it.pausedAccumulatedMs + chunk,
            )
        }
    }

    fun togglePause() {
        if (_state.value.paused) resumeSession() else pauseSession()
    }

    /** 거래소 진입 시 우리가 자동으로 걸었던 pause 인지 — 유저가 직접 pause 한 건 우리가 안 푼다. */
    private var pausedByExchange = false

    private fun enterExchange() {
        if (_state.value.inExchange) return
        if (!_state.value.paused) {
            pauseSession()
            pausedByExchange = true
        }
        _state.update { it.copy(inExchange = true) }
        refreshHoldings()
    }

    private fun exitExchange() {
        if (!_state.value.inExchange) return
        _state.update { it.copy(inExchange = false) }
        if (pausedByExchange) {
            resumeSession()
            pausedByExchange = false
        }
    }

    /** 현재 보유 아이템을 가치 내림차순으로 재계산해 state 에 반영. 수동 새로고침에서도 호출. */
    fun refreshHoldings() {
        _state.update { it.copy(holdings = computeHoldings()) }
    }

    /**
     * [slotLastCount] (슬롯별 절대 수량) 를 itemId 기준으로 합산해 가치순으로 정렬.
     * 이름을 모르는 아이템(item_names_ko.json 에 없음)은 노이즈라 제외하고,
     * 표시량도 상위 [MAX_HOLDINGS] 개로 제한한다.
     */
    private fun computeHoldings(): List<PickupSummary> {
        val totals = HashMap<String, Int>()
        for ((slotKey, qty) in slotLastCount) {
            if (qty <= 0) continue
            val itemId = extractItemId(slotKey) ?: continue
            totals[itemId] = (totals[itemId] ?: 0) + qty
        }
        return totals.mapNotNull { (itemId, qty) ->
            val info = itemInfo.lookup(itemId)
            val name = info?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val v = valueCalculator?.compute(itemId, qty)
            PickupSummary(
                timestampMs = System.currentTimeMillis(),
                action = "Holding",
                itemId = itemId,
                itemName = name,
                itemType = info.type,
                iconUrl = info.img.takeIf { it.isNotBlank() },
                quantity = qty,
                unitPrice = v?.unitPrice ?: 0f,
                value = v?.totalValue ?: 0.0,
                netValue = v?.netValue ?: 0.0,
            )
        }.sortedByDescending { it.value }.take(MAX_HOLDINGS)
    }

    /** 슬롯 키 두 형태 모두 처리: `<baseId>_<uuid>` 또는 fallback `page:slot:itemId`. */
    private fun extractItemId(slotKey: String): String? {
        if (slotKey.contains(':')) return slotKey.substringAfterLast(':')
        val underscoreIdx = slotKey.indexOf('_')
        return if (underscoreIdx > 0) slotKey.substring(0, underscoreIdx) else null
    }

    // 모바일 로그에서 뽑을 수 있는 필드 정규식들.
    private val levelInfoRegex = Regex("""LevelUid,\s*LevelType,\s*LevelId\s*=\s*(\d+)\s+(\d+)\s+(\d+)""")

    /**
     * 슬롯 상태 라인. 두 변종이 있고 포맷은 동일하다:
     * - `ItemChange@ Update Id=<baseId>_<uuid> BagNum=N in PageId=X SlotId=Y` — 기존 슬롯 수량 변경
     * - `ItemChange@ Add    Id=<baseId>_<uuid> BagNum=N in PageId=X SlotId=Y` — **빈 슬롯에 신규 배치**
     *
     * `Add` 를 놓치면 "처음 얻는 아이템 / 다 쓰고 다시 얻은 아이템" 의 획득이 통째로 유실된다
     * (예: 딥 스페이스 비콘을 전부 소모한 뒤 다시 드랍받는 경우).
     */
    private val slotLineRegex = Regex(
        """ItemChange@\s+(?:Update|Add)\s+Id=(\S+)\s+BagNum=(\d+)\s+in\s+PageId=(\d+)\s+SlotId=(\d+)"""
    )
    /**
     * 슬롯 전체 제거 — count 없이 `Delete` 만 찍힌다 (경매 등록, 장비 분해 등).
     * `Update`/`Add` 처럼 다음 줄 Modfy 를 기다릴 필요가 없다: Delete 자체가 이미 확정된
     * "이 슬롯 count=0" 이벤트라 바로 [handleModfy] 로 넘긴다.
     *
     * 실기기 로그 확인(2026-08-08, 경매장 판매 등록):
     *   ItemChange@ ProtoName=XchgForSale start
     *   ItemChange@ Delete Id=400008_<uuid> in PageId=103 SlotId=4
     *   BagMgr@:RemoveBagItem PageId = 103 SlotId = 4
     *   ItemChange@ ProtoName=XchgForSale end
     */
    private val slotDeleteRegex = Regex(
        """ItemChange@\s+Delete\s+Id=(\S+)\s+in\s+PageId=(\d+)\s+SlotId=(\d+)"""
    )
    // 실제 픽업 이벤트: BagMgr@:Modfy BagItem PageId = X SlotId = Y ConfigBaseId = Z Num = N
    private val modfyRegex = Regex("""BagMgr@:Modfy\s+BagItem\s+PageId\s*=\s*(\d+)\s+SlotId\s*=\s*(\d+)\s+ConfigBaseId\s*=\s*(\d+)\s+Num\s*=\s*(\d+)""")
    // 인벤 로드: BagMgr@:InitBagData PageId = X SlotId = Y ConfigBaseId = Z Num = N
    private val initBagFullRegex = Regex("""BagMgr@:InitBagData\s+PageId\s*=\s*(\d+)\s+SlotId\s*=\s*(\d+)\s+ConfigBaseId\s*=\s*(\d+)\s+Num\s*=\s*(\d+)""")

    /**
     * 직전 Update/Add 라인. 바로 다음 줄이 **같은 슬롯의 Modfy** 면 실제 변화 이벤트이고,
     * 그렇지 않으면 가방 스냅샷(로그인·귀환 시 대량 배치) 이므로 baseline 으로 확정한다.
     *
     * 17 MB 실측 로그 검증: Update/Add 484 건 중 163 건이 바로 다음 줄 Modfy 와 짝을 이루고
     * (= 실제 변화), 나머지 321 건은 전부 대량 스냅샷 구간에 있었다.
     */
    private class PendingSlot(
        val key: String,
        val pageId: String,
        val slotId: String,
        val bagNum: Int,
    )
    private var pendingSlot: PendingSlot? = null

    /**
     * `PageId:SlotId` → 슬롯 인스턴스 키(`<baseId>_<uuid>`).
     * InitBagData 라인에는 uuid 가 없어서 이 인덱스로 해석한다.
     * 대량 스냅샷에서 Update 배치가 InitBagData 배치보다 항상 먼저 오므로 커버리지 100% (실측 321/321).
     */
    private val slotKeyByPosition = HashMap<String, String>()

    // ItemChange@ ProtoName=X start / end 마커 감지 (assembler 밖에서도 in-block 상태 추적)
    private val pickItemsStartRegex = Regex("""ItemChange@\s+ProtoName=PickItems?\s+start""")
    private val pickItemsEndRegex = Regex("""ItemChange@\s+ProtoName=PickItems?\s+end""")
    // 소비 이벤트 블록 (맵 열기 = 재료 소비)
    private val consumeStartRegex = Regex("""ItemChange@\s+ProtoName=(Spv3Open|Spv3Enter|InputArea|XchgSyncSoldSale)\s+start""")
    private val consumeEndRegex = Regex("""ItemChange@\s+ProtoName=(Spv3Open|Spv3Enter|InputArea|XchgSyncSoldSale)\s+end""")

    /**
     * 맵 열기 = 이번 런의 시작. 여기서 나침반/비콘/탐침을 소비하므로
     * "이번 진입" 목록은 이 시점에 비워야 소비(마이너스) 항목이 목록에 남는다.
     *
     * 이전에는 OnEnterAreaBegin 에서 비웠는데, 실측 로그상 Spv3Open 소비 라인 ~100 ms 뒤에
     * OnEnterAreaBegin 이 오기 때문에 소비 항목이 화면에 뜨기도 전에 지워졌다.
     */
    private val mapOpenStartRegex = Regex("""ItemChange@\s+ProtoName=Spv3Open\s+start""")

    /**
     * 맵에서 나감(마을 귀환 등). 이전에는 회차 종료를 다음 [mapOpenStartRegex] (다음 맵 열기)
     * 에만 의존했는데, 맵을 나온 뒤 한동안 다음 맵을 안 열면 그 사이 시간이 전부 이번 회차
     * 경과시간으로 잡혀 무기한 늘어났다 (`MapRun.durationMs` 가 `endedAtMs ?: now` 라서).
     *
     * 실기기 로그 캡처(mems-check-2.log 등)로 확인: Spv3Open 으로 연 맵을 나올 때마다
     * `TLGame: Display: [Game] warning: AudioBGM OnExit Level` 이 정확히 한 번씩, 마을
     * LevelUid(111000) 재진입보다 먼저 찍힌다 — Spv3Open 이 없는 캡처(순수 마을/재접속)에는
     * 한 번도 안 나타나 맵 전용 신호로 보인다.
     */
    private val mapExitRegex = Regex("""AudioBGM OnExit Level""")

    /**
     * 거래소(경매장, AuctionHouseV2) 화면 진입/퇴장. `ItemChange@` 블록이 아니라 UI 페이지
     * 전환 로그라 별도 마커. 실기기 로그 캡처로 확인 (2026-08-08, 2회 진입/퇴장 모두 일치):
     *   TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Run
     *   TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Destory
     * "Destory" 는 게임 로그 원문의 오타 — 실제로 그렇게 찍힌다.
     */
    private val exchangeEnterRegex = Regex("""PageName\s*=\s*AuctionHouseV2\s*,\s*PageRunState\s*=\s*Run""")
    private val exchangeExitRegex = Regex("""PageName\s*=\s*AuctionHouseV2\s*,\s*PageRunState\s*=\s*Destory""")

    private enum class BlockContext { NONE, PICKUP, CONSUME }
    private var blockContext: BlockContext = BlockContext.NONE

    /**
     * 가방 전체 스냅샷 시작 신호 — 이걸 관측해야 픽업 계산이 시작된다.
     *
     * 세 가지 변종을 모두 받는다. 모바일 클라이언트에서는 `ResetItemsLayout` 이
     * 실측 로그(17 MB) 에 단 한 번도 안 나왔고, 실제로는
     * `ItemChange@ Reset PageId=<n>` → `Update` 배치 → `InitBagData` 배치 순서로 찍힌다.
     * `Reset` 이 배치의 맨 앞이라 가장 빨리 잡힌다.
     */
    private val bagSnapshotStartRegex = Regex(
        """ItemChange@\s+(?:ProtoName=ResetItemsLayout\s+start|Reset\s+PageId=)"""
    )

    // ── 경매장 시세 조회 (XchgSearchPrice) ────────────────────────────────────
    //
    // Send 에 조회 대상 아이템, Recv 에 호가 목록이 오고 **SynId 로 짝**을 맞춰야 한다.
    //
    //   ----Socket SendMessage STT----XchgSearchPrice----SynId = 31883
    //   +typ3 [63]
    //   +filters+1+refer [5040]              ← itemId
    //   ----Socket SendMessage End----
    //
    //   ----Socket RecvMessage STT----XchgSearchPrice----SynId = 31883
    //   +errCode
    //   +prices+1+currency [100300]          ← 화폐 (결정이어야 우리 단위와 일치)
    //   |      | +unitPrices+1 [3.0]         ← 최저가순 호가. 관측상 100 건
    //   |      | |          +2 [3.0]
    //   ----Socket RecvMessage End----
    private val xchgSendHeaderRegex = Regex("""SendMessage\s+STT----XchgSearchPrice----SynId\s*=\s*(\d+)""")
    private val xchgRecvHeaderRegex = Regex("""RecvMessage\s+STT----XchgSearchPrice----SynId\s*=\s*(\d+)""")
    private val socketEndRegex = Regex("""----Socket\s+\w+Message\s+End----""")
    private val xchgReferRegex = Regex("""\+filters\+\d+\+refer\s*\[(\d+)\]""")
    private val xchgCurrencyRegex = Regex("""\+currency\s*\[(\d+)\]""")
    /** `+<n> [<price>]` — `+prices+1+currency [..]` 처럼 숫자 뒤에 바로 `[` 가 안 오는 건 안 걸린다. */
    private val xchgUnitPriceRegex = Regex("""\+\d+\s+\[([0-9]*\.?[0-9]+)\]""")

    private enum class XchgMode { NONE, SEND, RECV }
    private var xchgMode = XchgMode.NONE
    private var xchgSynId: String? = null
    private var xchgLineCount = 0
    private var xchgInUnitPrices = false
    private var xchgCurrency: String? = null
    private val xchgUnitPrices = ArrayList<Double>(128)
    /** SynId → itemId. Send 와 Recv 를 잇는다. 오래된 건 알아서 밀려나게 크기 제한. */
    private val xchgItemBySynId = object : LinkedHashMap<String, String>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    // MapName tracking (기존)
    private val mapNameRegex = Regex("""(?:Load)?MapName\s*=\s*/Game/Art/Maps/[^/]+/([^/\s,]+)""")

    @Volatile
    private var latestMapCode: String? = null

    /**
     * 이번 런(맵 열기 → 다음 맵 열기) 동안 아이템별 누적 합계. key = itemId.
     * 맵을 열 때 초기화. 매 변화마다 갱신되고 state.recentPickups 를 여기서 정렬해서 만듦.
     */
    private val currentRunByItem = mutableMapOf<String, PickupSummary>()

    /**
     * 완료된 회차 **요약**. 아이템 목록은 디스크로 내려가 여기엔 없다.
     * 진행 중인 회차만 [currentRunByItem] 에 아이템을 들고 있다.
     */
    private val finishedRuns = mutableListOf<MapRun>()

    /** 세션 전체 아이템 합계. key = itemId. 증분 갱신 (회차 전체 재훑기 방지). */
    private val sessionItemTotals = LinkedHashMap<String, PickupSummary>()
    private var currentRunId: Long = -1
    private var currentRunStartedAtMs: Long = 0
    private var currentRunMapName: String? = null
    /**
     * 이번 회차가 실제 맵(Spv3Open)으로 열렸는지, 맵 열기 전 획득을 담는 암묵적 회차
     * ([ensureCurrentRun]) 인지. [SessionState.mapElapsedMs] 가 "진짜 맵 안에 있던 시간"만
     * 세려면 마을 등에서의 암묵적 회차를 걸러내야 해서 필요하다.
     */
    private var currentRunIsMapRun: Boolean = false
    private var nextRunId: Long = 1

    /**
     * 인벤토리 슬롯 별 마지막 관측 count. 실제 획득량(delta) 계산에 사용.
     * key: `<baseId>_<UUID>` (한 슬롯 인스턴스에 고정 UUID 가 붙고, 가방 정렬로도 안 바뀜)
     */
    private val slotLastCount = HashMap<String, Int>()

    /**
     * Assembler 에서 완성된 메시지를 소비.
     *
     * 헤더 종류 별로 분기:
     * - ItemChange: PickItem(s) 이면 payload 에서 ConfigBaseId 추출 → 픽업 카운트
     * - EnterAreaBegin: mapsEntered 카운트 + LevelId 표시
     */
    fun consume(msg: MessageAssembler.RawMessage) {
        when (msg.header.kind) {
            // ItemChange 는 이제 observeLine 의 Modfy 감지로 처리 → 여기서 중복 처리 안 함
            HeaderKind.ItemChange -> Unit
            HeaderKind.EnterAreaBegin -> handleEnterArea(msg)
        }
    }

    /**
     * assembler 를 통과하지 않는 라인들도 스캔해서 슬롯 변화를 직접 집계한다.
     *
     * 라인 종류:
     * - `ItemChange@ Update|Add Id=… BagNum=N in PageId=X SlotId=Y` — 슬롯 상태 라인.
     *   바로 다음 줄이 같은 슬롯 Modfy 면 **실제 변화**, 아니면 **가방 스냅샷 baseline**.
     * - `BagMgr@:Modfy …` — 확정된 변화. 직전 슬롯 라인의 uuid 와 묶어서 delta 계산.
     * - `BagMgr@:InitBagData …` — 가방 스냅샷 baseline (uuid 는 position 인덱스로 해석).
     * - `MapName = …` — 맵 코드네임.
     */
    fun observeLine(line: String) {
        // ── 빠른 사전 필터 ──────────────────────────────────────────────
        // 실측(157,351 줄): 관심 대상은 1.25% 뿐이고 98.7% 는 렌더러/오디오 로그다.
        // 그 전부에 정규식 10 개를 돌리면 플레이 중 초당 1,000 회 매칭이 헛돈다.
        // 부분 문자열 검사는 정규식보다 훨씬 싸므로 여기서 먼저 걸러낸다.
        //
        // 경매장 블록(RECV) 안에서는 `|  +12 [3.0]` 같은 연속 줄에 키워드가 없으므로
        // 필터를 건너뛰어야 한다.
        if (xchgMode == XchgMode.NONE && !isInteresting(line)) return

        // 경매장 조회 블록 안이면 그쪽에서 소비. (블록은 짧고 다른 이벤트가 끼지 않는다)
        if (consumeXchgLine(line)) return

        // 블록 컨텍스트 추적 (Modfy 첫 sighting 시 방향 힌트)
        when {
            pickItemsStartRegex.containsMatchIn(line) -> blockContext = BlockContext.PICKUP
            consumeStartRegex.containsMatchIn(line) -> blockContext = BlockContext.CONSUME
            pickItemsEndRegex.containsMatchIn(line) -> blockContext = BlockContext.NONE
            consumeEndRegex.containsMatchIn(line) -> blockContext = BlockContext.NONE
        }

        // 거래소 진입/퇴장 — 파밍 집계와 무관한 UI 전환이므로 다른 파싱과 독립적으로 처리.
        if (exchangeEnterRegex.containsMatchIn(line)) { enterExchange(); return }
        if (exchangeExitRegex.containsMatchIn(line)) { exitExchange(); return }

        // 맵 열기 = 새 런 시작. 이 직후의 소비(마이너스) 항목부터 "이번 진입" 에 쌓인다.
        if (mapOpenStartRegex.containsMatchIn(line)) startNewRun()

        // 맵에서 나감 = 진행 중이던 회차를 여기서 닫는다. 다음 맵을 바로 안 열어도
        // (마을에서 정비 등) 경과시간이 그 사이 계속 늘어나지 않게.
        if (mapExitRegex.containsMatchIn(line)) endCurrentRunOnMapExit()

        // 가방 스냅샷 시작 → 계산 시작 (게임 응답 배치를 다 기다리지 않고 바로)
        if (bagSnapshotStartRegex.containsMatchIn(line)) {
            flushPendingSlotAsBaseline()
            markBaselineReady()
            return
        }

        // A) Update / Add → 다음 줄이 같은 슬롯 Modfy 인지 보고 판정 (변화 vs baseline)
        val slot = slotLineRegex.find(line)
        if (slot != null) {
            flushPendingSlotAsBaseline()
            val key = slot.groupValues[1]
            val bagNum = slot.groupValues[2].toIntOrNull()
            val pageId = slot.groupValues[3]
            val slotId = slot.groupValues[4]
            updatePositionKey(pageId, slotId, key)
            if (bagNum != null) pendingSlot = PendingSlot(key, pageId, slotId, bagNum)
            return
        }

        // A2) Delete → 슬롯 전체 제거. count=0 으로 확정이라 lookahead 없이 바로 처리.
        val del = slotDeleteRegex.find(line)
        if (del != null) {
            flushPendingSlotAsBaseline()
            val key = del.groupValues[1]
            val pageId = del.groupValues[2]
            val slotId = del.groupValues[3]
            val itemId = extractItemId(key) ?: key
            // 이 슬롯이 이번 세션에서 baseline 합성 키(page:slot:itemId)로만 잡혀 있고
            // 실거래 Update/Add 로 uuid 키 재확인이 한 번도 없었다면, updatePositionKey 없이
            // 바로 아래 handleModfy 를 부르면 옛 합성 키가 slotLastCount 에 양수로 남아
            // computeHoldings() 합산에서 유령 보유량으로 잡힌다 — 팔았는데 계속 보유 목록에
            // 남는 버그. Update/Add 경로(위 378줄)와 똑같이 포지션 점유 키부터 정리한다.
            updatePositionKey(pageId, slotId, key)
            handleModfy(
                slotUuid = key,
                itemId = itemId,
                pageId = pageId,
                totalCountInSlot = 0,
                timestampMs = System.currentTimeMillis(),
            )
            return
        }

        // B) Modfy → 실제 변화 이벤트 (PickItems 블록 유무와 무관하게)
        val modfy = modfyRegex.find(line)
        if (modfy != null) {
            val pageId = modfy.groupValues[1]
            val slotId = modfy.groupValues[2]
            val itemId = modfy.groupValues[3]
            val currentCount = modfy.groupValues[4].toIntOrNull()

            val p = pendingSlot
            pendingSlot = null
            val matchedPending: Boolean
            val key: String
            if (p != null && p.pageId == pageId && p.slotId == slotId) {
                matchedPending = true
                key = p.key
            } else {
                // 짝이 안 맞으면 그 pending 은 스냅샷이었던 것 → baseline 으로 확정하고
                // 이번 슬롯은 position 인덱스(또는 position+itemId) 로 식별.
                if (p != null) writeBaseline(p)
                matchedPending = false
                key = resolveSlotKey(pageId, slotId, itemId)
            }

            if (currentCount == null) return
            handleModfy(
                slotUuid = key,
                itemId = itemId,
                pageId = pageId,
                totalCountInSlot = currentCount,
                timestampMs = System.currentTimeMillis(),
                confirmedChange = matchedPending,
            )
            return
        }

        // C) InitBagData → 가방 스냅샷. baseline 만 세팅하고 이벤트로는 세지 않는다.
        val init = initBagFullRegex.find(line)
        if (init != null) {
            flushPendingSlotAsBaseline()
            val pageId = init.groupValues[1]
            val slotId = init.groupValues[2]
            val itemId = init.groupValues[3]
            val currentCount = init.groupValues[4].toIntOrNull() ?: return
            slotLastCount[resolveSlotKey(pageId, slotId, itemId)] = currentCount
            markBaselineReady()
            return
        }

        // D) 그 외 라인 → 대기 중이던 Update/Add 는 Modfy 가 안 붙었으므로 스냅샷 확정
        flushPendingSlotAsBaseline()

        // E) MapName 추적 (내부, UI 표시는 제거됨)
        val m = mapNameRegex.find(line)
        if (m != null) {
            val code = m.groupValues[1]
            if (code != latestMapCode && code.isNotEmpty() && !code.startsWith("LoginScene")) {
                latestMapCode = code
            }
        }
    }

    /**
     * 정규식을 돌릴 가치가 있는 라인인가. 이 앱이 보는 모든 이벤트는 아래 토큰 중 하나를 포함한다.
     *
     * - `ItemChange@`  — 슬롯 Update/Add/Reset, ProtoName 블록 마커
     * - `BagMgr@:`     — Modfy / InitBagData
     * - `----Socket`   — 경매장 조회 블록 시작·끝
     * - `MapName`      — 맵 코드네임
     * - `OnEnterArea`  — 맵 진입
     * - `AuctionHouseV2` — 거래소 화면 진입/퇴장
     * - `OnExit Level` — 맵에서 나감
     */
    private fun isInteresting(line: String): Boolean =
        line.contains("ItemChange@") ||
        line.contains("BagMgr@:") ||
        line.contains("----Socket") ||
        line.contains("MapName") ||
        line.contains("OnEnterArea") ||
        line.contains("AuctionHouseV2") ||
        line.contains("OnExit Level")

    /**
     * 경매장 시세 조회(`XchgSearchPrice`) 블록 처리.
     *
     * @return 이 라인을 소비했으면 true (호출측은 더 볼 필요 없음)
     */
    private fun consumeXchgLine(line: String): Boolean {
        // 1) 블록 시작
        xchgSendHeaderRegex.find(line)?.let {
            flushPendingSlotAsBaseline()
            xchgMode = XchgMode.SEND
            xchgSynId = it.groupValues[1]
            xchgLineCount = 0
            return true
        }
        xchgRecvHeaderRegex.find(line)?.let {
            flushPendingSlotAsBaseline()
            xchgMode = XchgMode.RECV
            xchgSynId = it.groupValues[1]
            xchgLineCount = 0
            xchgInUnitPrices = false
            xchgCurrency = null
            xchgUnitPrices.clear()
            return true
        }

        if (xchgMode == XchgMode.NONE) return false

        // 2) 블록 종료
        if (socketEndRegex.containsMatchIn(line)) {
            if (xchgMode == XchgMode.RECV) finishXchgRecv()
            xchgMode = XchgMode.NONE
            xchgSynId = null
            return true
        }

        // End 라인을 못 만나는 이상 상황에서 무한히 삼키지 않도록 가드.
        if (++xchgLineCount > MAX_XCHG_BLOCK_LINES) {
            xchgMode = XchgMode.NONE
            xchgSynId = null
            return false
        }

        when (xchgMode) {
            XchgMode.SEND -> {
                val refer = xchgReferRegex.find(line)?.groupValues?.get(1)
                val syn = xchgSynId
                if (refer != null && syn != null) xchgItemBySynId[syn] = refer
            }
            XchgMode.RECV -> {
                xchgCurrencyRegex.find(line)?.let { xchgCurrency = it.groupValues[1] }
                // unitPrices 배열은 첫 줄에 `+unitPrices+1 [..]`, 이후 `|` 로 이어지는 연속 줄.
                when {
                    line.contains("+unitPrices") -> xchgInUnitPrices = true
                    !line.trimStart().startsWith("|") -> xchgInUnitPrices = false
                }
                if (xchgInUnitPrices) {
                    for (m in xchgUnitPriceRegex.findAll(line)) {
                        m.groupValues[1].toDoubleOrNull()?.let { xchgUnitPrices += it }
                    }
                }
            }
            XchgMode.NONE -> Unit
        }
        return true
    }

    private fun finishXchgRecv() {
        val itemId = xchgSynId?.let { xchgItemBySynId.remove(it) } ?: return
        if (xchgUnitPrices.isEmpty()) return
        // 결정(100300) 으로 매겨진 호가만 쓴다. 가루(100200) 거래는 계산에서 제외.
        if (xchgCurrency != null && xchgCurrency != BASE_CURRENCY_ID) {
            xchgUnitPrices.clear()
            return
        }
        observedPrices?.record(
            itemId = itemId,
            unitPrices = xchgUnitPrices.toList(),
            observedAtMs = System.currentTimeMillis(),
            currencyId = xchgCurrency,
        )
        xchgUnitPrices.clear()
    }

    /**
     * `PageId:SlotId` 로 슬롯 인스턴스 키를 찾는다.
     * 인덱스가 가리키는 uuid 의 baseId 접두사가 지금 아이템과 다르면 (슬롯 재사용) 인덱스를 믿지 않고
     * `page:slot:itemId` 합성 키를 쓴다.
     */
    private fun resolveSlotKey(pageId: String, slotId: String, itemId: String): String {
        val indexed = slotKeyByPosition["$pageId:$slotId"]
        val key = if (indexed != null && indexed.startsWith("${itemId}_")) indexed
                  else "$pageId:$slotId:$itemId"
        updatePositionKey(pageId, slotId, key)
        return key
    }

    /**
     * `PageId:SlotId` 포지션의 현재 점유 키를 갱신한다.
     *
     * 한 포지션은 항상 하나의 키만 점유해야 하는데, uuid 직접 파싱 경로(케이스 A)와
     * [resolveSlotKey] 의 합성 키 fallback 경로가 **같은 물리 슬롯을 서로 다른 키 문자열로**
     * 기록할 수 있다 (예: baseline(InitBagData) 때는 fallback 합성 키로 잡혔다가, 이후 실거래
     * 이벤트는 항상 uuid 키로 옴).
     *
     * 새 키가 **같은 아이템**이면 (합성 키 ↔ uuid 키 전환일 뿐, 물리적으로 같은 슬롯) 예전 키의
     * 수량을 새 키로 그대로 이어받는다 — 그냥 0으로 지우면 baseline 때 잡힌 수량 정보가
     * 사라져서, 그 다음 실거래 Modfy 가 "이 키는 처음 본다" 고 오판하고 **기존 보유량까지
     * 통째로 신규 획득으로** 잡아버린다 (실측: baseline 581개 상태에서 실제로는 +3개만
     * 늘었는데 확정 픽업 이벤트가 새 키로 들어오는 바람에 584개 전부를 "이번에 획득" 으로
     * 잡아 회차 합계가 592로 부풀려진 버그).
     *
     * 새 키가 **다른 아이템**이면 (슬롯이 재사용된 것) 예전 키는 이 포지션과 무관해졌으니 0으로
     * 무효화한다 — 그 아이템이 실제로 다른 슬롯으로 옮겨간 경우엔 그 슬롯의 Update 라인이
     * (같은 배치 안에서) 다시 정확한 수량으로 채워준다. 이렇게 안 하면 두 키가 각각
     * [slotLastCount] 에 남아 [computeHoldings] 의 itemId 합산에서 같은 물리 아이템이
     * 이중으로 잡힌다 (실측: 8개 보유 중 1개 구매 → 9개가 아니라 17개=8+9 로 표시되던 버그).
     */
    private fun updatePositionKey(pageId: String, slotId: String, newKey: String) {
        val posKey = "$pageId:$slotId"
        val oldKey = slotKeyByPosition[posKey]
        if (oldKey != null && oldKey != newKey) {
            val oldCount = slotLastCount.remove(oldKey)
            val sameItem = extractItemId(oldKey) != null && extractItemId(oldKey) == extractItemId(newKey)
            if (sameItem && oldCount != null && !slotLastCount.containsKey(newKey)) {
                slotLastCount[newKey] = oldCount
            }
        }
        slotKeyByPosition[posKey] = newKey
    }

    /** 대기 중인 슬롯 라인을 baseline 으로 확정 (변화 이벤트가 아니었다는 뜻). */
    private fun flushPendingSlotAsBaseline() {
        val p = pendingSlot ?: return
        pendingSlot = null
        writeBaseline(p)
    }

    private fun writeBaseline(p: PendingSlot) {
        slotLastCount[p.key] = p.bagNum
    }

    /** 가방 스냅샷 관측 → 계산 시작. 경과 시간도 이 시점부터 센다. */
    private fun markBaselineReady() {
        if (_state.value.baselineReady) return
        _state.update {
            if (it.baselineReady) it
            else it.copy(baselineReady = true, startedAtMs = System.currentTimeMillis())
        }
    }

    /**
     * 맵에서 나감([mapExitRegex]) — 진행 중인 회차를 여기서 닫는다.
     *
     * [startNewRun] 과 달리 새 회차를 열지는 않는다 — 마을에서 주운 것은 다음 [handleModfy]
     * 가 [ensureCurrentRun] 으로, 다음 맵은 다음 [startNewRun] 이 각자 알아서 새로 연다.
     * 이미 닫혀 있으면(currentRunId < 0) [closeCurrentRun] 이 알아서 아무 것도 안 한다.
     *
     * 이 신호 자체가 "방금 그 회차는 진짜 맵이었다"는 증거다 — 실기기 캡처 전부에서 Spv3Open
     * 없이는 한 번도 안 나타났다. 그래서 폴링이 맵 한가운데서 (재)시작돼 그 맵의 Spv3Open을
     * 놓쳐 [ensureCurrentRun] 이 암묵적 회차(`isMapRun=false`)로 잡았더라도, 여기서 소급
     * 확정해준다 — 안 그러면 그 맵 전체가 영영 M(매핑)타임에서 빠진다(실기기 재현: 앱 재시작
     * 직후 첫 맵만 M경과가 0에 고정).
     *
     * 포탈로 바로 다음 맵을 열 때, **직전 씬을 나가는 이 신호가 새 맵 자신의 Spv3Open 이 연
     * 회차와 같은 전환 블록 안에(~100ms 차이로) 같이 찍히는 경우가 실기기에서 확인됐다** —
     * 그대로 닫으면 방금 연 회차(맵 열기 소비만 담김)와 그 이후 실제 파밍 아이템이 담길 새
     * 암묵적 회차가 쪼개져서, 맵 하나를 돌았는데 회차가 2개로 나뉘고 판당 평균수익도 반토막
     * 난다. 회차가 열린 지 얼마 안 됐으면([MAP_EXIT_GRACE_MS] 이내) 이 신호는 전환 잡음으로
     * 보고 무시한다 — 진짜 나가는 신호라면 다음 Spv3Open 이나 그다음 진짜 나가는 신호가 늦게
     * 닫아줄 뿐, 안 닫히고 사라지진 않는다.
     */
    private fun endCurrentRunOnMapExit() {
        if (currentRunId < 0) return
        if (System.currentTimeMillis() - currentRunStartedAtMs < MAP_EXIT_GRACE_MS) return
        currentRunIsMapRun = true
        closeCurrentRun()
        publishRuns()
    }

    /** 새 맵 시작 — 진행 중이던 회차를 닫아 기록으로 넘기고, "이번 맵" 을 비운다. */
    private fun startNewRun() {
        closeCurrentRun()
        currentRunId = nextRunId++
        currentRunStartedAtMs = System.currentTimeMillis()
        currentRunMapName = null
        currentRunIsMapRun = true
        currentRunByItem.clear()
        publishRuns()
    }

    /**
     * 진행 중인 회차를 완료 처리한다. 빈 회차는 버린다.
     *
     * 아이템 목록은 [onRunFinished] 로 디스크에 내리고 **메모리에는 요약만** 남긴다.
     * 회차가 늘어도 프로세스 메모리가 선형으로 늘지 않게 하는 핵심 지점.
     */
    private fun closeCurrentRun() {
        if (currentRunId < 0) return
        if (currentRunByItem.isNotEmpty()) {
            val run = buildRun(endedAtMs = System.currentTimeMillis())
            onRunFinished?.invoke(run)
            finishedRuns += run.toSummary()
            // 메모리 상한. 디스크 쪽 상한은 RunRepository.MAX_STORED_RUNS.
            while (finishedRuns.size > MAX_RUNS) finishedRuns.removeAt(0)
        }
        currentRunId = -1
    }

    /** 서비스 재시작 시 디스크에 있던 회차 요약을 복원. */
    fun restoreRuns(summaries: List<MapRun>, sessionItems: List<PickupSummary>) {
        finishedRuns.clear()
        finishedRuns += summaries
        nextRunId = (summaries.maxOfOrNull { it.id } ?: 0L) + 1
        sessionItemTotals.clear()
        for (p in sessionItems) p.itemId?.let { sessionItemTotals[it] = p }
        publishRuns()
    }

    /** 아직 회차가 시작되지 않았으면(맵 열기 전 획득) 암묵적으로 하나 연다. 맵 안이 아니므로 M타임엔 안 잡힌다. */
    private fun ensureCurrentRun(atMs: Long) {
        if (currentRunId >= 0) return
        currentRunId = nextRunId++
        currentRunStartedAtMs = atMs
        currentRunMapName = null
        currentRunIsMapRun = false
    }

    private fun buildRun(endedAtMs: Long?): MapRun = MapRun(
        id = currentRunId,
        index = 0,   // publishRuns 에서 다시 매긴다
        startedAtMs = currentRunStartedAtMs,
        endedAtMs = endedAtMs,
        mapName = currentRunMapName,
        isMapRun = currentRunIsMapRun,
        items = currentRunByItem.values.sortedByDescending { kotlin.math.abs(it.value) },
    )

    /** [finishedRuns] + 진행 중 회차를 state 로 반영. 회차 번호를 1 부터 다시 매긴다. */
    private fun publishRuns() {
        val all = buildList {
            addAll(finishedRuns)
            if (currentRunId >= 0) add(buildRun(endedAtMs = null))
        }.mapIndexed { i, r -> r.copy(index = i + 1) }

        val items = sessionItemTotals.values.sortedByDescending { kotlin.math.abs(it.value) }

        _state.update { s ->
            val current = all.lastOrNull()?.takeIf { it.inProgress }
            s.copy(
                runs = all,
                sessionItems = items,
                recentPickups = current?.items?.take(MAX_RECENT_PICKUPS) ?: emptyList(),
                currentMapValue = current?.totalValue ?: 0.0,
                totalValue = all.sumOf { it.totalValue },
                netTotalValue = all.sumOf { it.netTotalValue },
                pickupCount = all.sumOf { it.pickupCount },
            )
        }
    }

    /** 세션 전체 아이템 합계를 증분으로 갱신. */
    private fun addToSessionTotals(itemId: String, delta: PickupSummary) {
        val prev = sessionItemTotals[itemId]
        sessionItemTotals[itemId] = if (prev == null) delta
        else prev.copy(
            timestampMs = delta.timestampMs,
            quantity = prev.quantity + delta.quantity,
            value = prev.value + delta.value,
            netValue = prev.netValue + delta.netValue,
            unitPrice = delta.unitPrice.takeIf { it > 0f } ?: prev.unitPrice,
        )
    }

    /** 회차 삭제 시 그 회차 몫을 세션 합계에서 뺀다. */
    private fun subtractFromSessionTotals(items: List<PickupSummary>) {
        for (p in items) {
            val id = p.itemId ?: continue
            val prev = sessionItemTotals[id] ?: continue
            val q = prev.quantity - p.quantity
            val v = prev.value - p.value
            val nv = prev.netValue - p.netValue
            if (q == 0 && kotlin.math.abs(v) < 1e-9) sessionItemTotals.remove(id)
            else sessionItemTotals[id] = prev.copy(quantity = q, value = v, netValue = nv)
        }
    }

    /**
     * 회차 삭제 — 잘못 집계된 회차를 통째로 제거한다.
     * 세션 총 수익/픽업 수/아이템 합계도 남은 회차 기준으로 다시 계산된다.
     *
     * @param items 그 회차의 아이템 목록. 완료된 회차는 메모리에 없으므로
     *              호출측(서비스)이 디스크에서 읽어 넘겨준다.
     */
    fun deleteRun(runId: Long, items: List<PickupSummary> = emptyList()) {
        if (currentRunId == runId) {
            subtractFromSessionTotals(currentRunByItem.values.toList())
            currentRunByItem.clear()
            currentRunId = -1
        } else if (finishedRuns.any { it.id == runId }) {
            subtractFromSessionTotals(items)
            finishedRuns.removeAll { it.id == runId }
        }
        publishRuns()
    }

    /**
     * BagMgr@:Modfy 라인 = 실제 픽업 (또는 소비/변화).
     * PickItems 블록 유무와 상관없이 모든 Modfy 를 처리.
     */
    private fun handleModfy(
        slotUuid: String,
        itemId: String,
        pageId: String,
        totalCountInSlot: Int,
        timestampMs: Long,
        /** 바로 앞 Update/Add 라인이 같은 슬롯에 대해 이 Modfy 와 직접 짝지어졌는지 (실제 변화 확정). */
        confirmedChange: Boolean = false,
    ) {
        // 가방 정렬 관측 전 = 슬롯 기준 수량 미확보 → 계산 안 하고 baseline 만 쌓는다.
        if (!_state.value.baselineReady) {
            slotLastCount[slotUuid] = totalCountInSlot
            return
        }
        // 맵을 열기 전에 얻은 것도 버리지 않고 암묵적 회차로 담는다.
        ensureCurrentRun(timestampMs)
        // 일시정지 중이면 slot 상태만 갱신하고 픽업 카운트 안 함.
        // slotLastCount 는 계속 최신값 유지해야 resume 후 다음 Modfy delta 가 정확.
        if (_state.value.paused) {
            slotLastCount[slotUuid] = totalCountInSlot
            // 거래소 안이면(구매/판매 등록/취소로 슬롯이 바뀔 때마다) 수동 새로고침 없이도
            // 보유 아이템 가치가 바로 최신으로 보이게 즉시 재계산한다.
            if (_state.value.inExchange) refreshHoldings()
            return
        }

        val prev = slotLastCount[slotUuid]
        slotLastCount[slotUuid] = totalCountInSlot
        val quantity: Int = if (prev == null) {
            if (confirmedChange && blockContext != BlockContext.CONSUME) {
                // 바로 앞줄 Update/Add 가 이 Modfy 와 직접 짝지어져 실제 변화라고 확정됐는데
                // 이 슬롯을 처음 본다 = baseline 스냅샷엔 없던 완전히 새 슬롯이 방금 생긴 것.
                // 0 → count 전체를 델타로 인정해야 스택으로 여러 개 한 번에 드랍되는 경우
                // (예: 신규 아이템 3개 동시 획득) 를 1개로 과소 집계하지 않는다.
                totalCountInSlot
            } else {
                // 확정 못 한 경로(합성 키 fallback 등)거나 소비 컨텍스트 — 과거 상태를 모르니
                // 안전하게 ±1 로만 카운트해서 절대 오카운트 안 되게 한다.
                //   PICKUP: +1 (신규 드롭 최소값)
                //   CONSUME: -1 (소비 최소값 — totalCountInSlot 은 소비 "이후" 남은 값이라
                //             델타로 못 씀)
                //   NONE (블록 밖): +1 로 간주
                when (blockContext) {
                    BlockContext.PICKUP -> 1
                    BlockContext.CONSUME -> -1
                    BlockContext.NONE -> 1
                }
            }
        } else {
            val delta = totalCountInSlot - prev
            if (delta == 0) return
            delta
        }

        val info = itemInfo.lookup(itemId)
        val valueResult = valueCalculator?.compute(itemId, quantity)
        val unitPrice = valueResult?.unitPrice ?: 0f
        val totalValue = valueResult?.totalValue ?: 0.0
        val netValue = valueResult?.netValue ?: 0.0
        val priced = valueResult?.priced ?: false

        val summary = PickupSummary(
            timestampMs = timestampMs,
            action = "PickItems",
            itemId = itemId,
            itemName = info?.name,
            itemType = info?.type ?: pageIdLabel(pageId),
            iconUrl = info?.img?.takeIf { it.isNotBlank() },
            quantity = quantity,
            unitPrice = unitPrice,
            value = totalValue,
            netValue = netValue,
        )

        // 이번 맵 안 아이템별 누적. 같은 itemId 재관측이면 quantity/value 합산.
        val prevAgg = currentRunByItem[itemId]
        val merged = if (prevAgg == null) summary
        else prevAgg.copy(
            timestampMs = timestampMs,             // 마지막 관측 시각으로 갱신
            quantity = prevAgg.quantity + summary.quantity,
            value = prevAgg.value + summary.value,
            netValue = prevAgg.netValue + summary.netValue,
            unitPrice = summary.unitPrice.takeIf { it > 0f } ?: prevAgg.unitPrice,
        )
        currentRunByItem[itemId] = merged
        addToSessionTotals(itemId, summary)

        if (!_state.value.active) return

        // 미가격 카운트와 시계열은 회차 삭제와 무관하게 누적만 한다.
        _state.update { s ->
            val newTotal = s.runs.sumOf { it.totalValue } + totalValue
            s.copy(
                unpricedPickups = if (priced || itemId.isBlank()) s.unpricedPickups
                                  else s.unpricedPickups + 1,
                valueSeries = updateValueSeries(s.valueSeries, timestampMs, newTotal),
            )
        }
        // 총 수익/픽업 수/이번 맵 목록은 전부 회차 목록에서 파생시킨다.
        publishRuns()
    }

    /**
     * 1분 단위 슬롯에 누적 가치 샘플링. 같은 슬롯이면 마지막 값 갱신, 아니면 새 슬롯 추가.
     * 최대 60개까지 슬라이딩 (최근 1시간).
     */
    private fun updateValueSeries(
        current: List<TimeSample>,
        eventTs: Long,
        cumulative: Double,
    ): List<TimeSample> {
        val slot = (eventTs / 60_000L) * 60_000L
        val list = current.toMutableList()
        val last = list.lastOrNull()
        if (last != null && last.timeSlotMs == slot) {
            list[list.size - 1] = TimeSample(slot, cumulative)
        } else {
            list += TimeSample(slot, cumulative)
            while (list.size > MAX_TIME_SAMPLES) list.removeAt(0)
        }
        return list
    }

    private fun handleEnterArea(msg: MessageAssembler.RawMessage) {
        // payload 안에서 LevelUid/LevelType/LevelId 추출
        var levelUid: String? = null
        var levelType: String? = null
        var levelId: String? = null
        for (line in msg.data) {
            levelInfoRegex.find(line)?.let {
                levelUid = it.groupValues[1]
                levelType = it.groupValues[2]
                levelId = it.groupValues[3]
            }
            if (levelId != null) break
        }

        val newArea = levelUid ?: levelId
        // 게임이 한 번의 맵 진입에도 여러 OnEnterAreaBegin 을 emit 하므로 areaId 로 dedupe.
        // (예: 로딩 단계 → 로딩 완료 → 실제 게임플레이 각각 fire)
        val nowMs = msg.header.timestampEpochMs

        _state.update { s ->
            if (!s.active || s.paused) return@update s
            val prevArea = s.currentAreaId
            val lastEnterMs = s.lastEnterAreaMs

            // areaId 를 모르면 count 하지 않음 (파싱 실패 케이스)
            if (newArea == null) return@update s

            // 같은 areaId 로 재-트리거되면 무시
            if (prevArea == newArea) return@update s

            // 같은 areaId 없이 매우 짧은 간격(3초 이하) 안에 두 번 오면 게임의 중복 emit 로 간주
            if (prevArea == null && lastEnterMs > 0 && nowMs - lastEnterMs < 3000) return@update s

            val completed = if (prevArea != null) s.mapsCompleted + 1 else s.mapsCompleted
            // 이름 우선순위: map.json/map_alias.json 큐레이션 매핑 → 관측된 MapName code 원본
            // → itemInfo 매핑 → levelId 자체
            val curatedMapName = mapNames?.resolve(latestMapCode, levelId)
            val displayName = curatedMapName
                ?: latestMapCode
                ?: levelId?.let { itemInfo.lookup(it)?.name }
                ?: levelId
            // 진행 중인 회차에 맵 이름을 달아준다 (회차 목록/그래프 라벨용).
            if (currentRunMapName == null) currentRunMapName = displayName
            // map.json/map_alias.json 은 실제 농사 맵(감시자 던전 등)만 커버하고 마을은 안
            // 걸린다 — 그래서 매칭 성공을 "진짜 맵 안" 확정으로 써도 안전하다. 폴링이 맵
            // 중간에 (재)시작돼 그 맵의 Spv3Open 을 놓쳐 암묵적 회차로 잡혔더라도, [endCurrentRunOnMapExit]
            // 처럼 나갈 때까지 기다리지 않고 여기서 바로 확정해서 M타임이 입장 시점부터 실시간으로 붙는다.
            if (currentRunId >= 0 && !currentRunIsMapRun && curatedMapName != null) {
                currentRunIsMapRun = true
            }
            // "이번 진입" 목록은 여기서 비우지 않는다. 맵 열기(Spv3Open) 직후 ~100 ms 만에
            // 이 이벤트가 오기 때문에, 여기서 비우면 방금 기록한 소비(마이너스) 가 사라진다.
            // 목록 초기화는 startNewRun() 이 Spv3Open 시점에 수행.
            s.copy(
                mapsEntered = s.mapsEntered + 1,
                mapsCompleted = completed,
                currentAreaId = newArea,
                currentMapId = levelId,
                currentMapName = displayName,
                lastEnterAreaMs = nowMs,
            )
        }
    }

    private fun pageIdLabel(pageId: String?): String? = when (pageId) {
        "100" -> "장비 백팩"
        "101" -> "스킬 백팩"
        "102" -> "통화 백팩"
        "103" -> "기타 백팩"
        else -> null
    }

    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        while (true) {
            val prev = value
            val next = block(prev)
            if (compareAndSet(prev, next)) return
        }
    }

    companion object {
        const val MAX_RECENT_PICKUPS = 10
        /** 가치 기준 통화 = 최초의 불꽃 결정. */
        private const val BASE_CURRENCY_ID = "100300"
        /** 경매장 블록이 비정상적으로 길면 파싱을 포기 (End 라인 유실 대비). */
        private const val MAX_XCHG_BLOCK_LINES = 400
        /** 보관할 최대 회차 수. 넘으면 오래된 것부터 버린다. */
        private const val MAX_RUNS = 200
        /**
         * [endCurrentRunOnMapExit] 무시 임계값. 실기기 실측: 포탈로 바로 다음 맵을 열 때
         * "OnExit Level" 이 그 맵의 Spv3Open 으로부터 ~100ms 뒤 같은 전환 블록 안에 찍혔다 —
         * 여유를 크게 둬도 진짜 파밍 플레이 시간(최소 몇 초~수십 초)과는 자릿수 차이가 나서
         * 오탐 걱정은 없다.
         */
        private const val MAP_EXIT_GRACE_MS = 500L
        /** "가치" 탭/HUD 에 보여줄 최대 보유 아이템 종류 수 (가치 내림차순으로 자름). */
        private const val MAX_HOLDINGS = 50
        const val MAX_TIME_SAMPLES = 60   // 1분당 1점 × 60 = 최근 1시간
    }
}
