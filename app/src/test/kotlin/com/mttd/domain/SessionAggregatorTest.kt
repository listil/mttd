package com.mttd.domain

import com.mttd.data.items.ItemInfoLookup
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SessionAggregator] 회귀 테스트.
 *
 * 실기기 로그 캡처로 검증된 실제 버그 재현 케이스 위주 — 특히 첫 sighting 스택 픽업
 * 과소집계, 슬롯 재식별(baseline 합성 키 ↔ 실거래 uuid 키) 시 과대집계, 거래소
 * 진입/퇴장 pause 연동. 아이템 이름/시세는 검증 대상이 아니라 [itemInfo] 는
 * mock 으로 항상 채워진 값을 반환하게 한다.
 */
class SessionAggregatorTest {

    private lateinit var itemInfo: ItemInfoLookup
    private lateinit var aggregator: SessionAggregator

    @Before
    fun setUp() {
        itemInfo = mockk()
        every { itemInfo.lookup(any()) } returns
            ItemInfoLookup.ItemInfo(name = "TestItem", type = "Test", img = "")
        aggregator = SessionAggregator(itemInfo = itemInfo, valueCalculator = null)
    }

    /** 가방 정렬(Reset) 전에는 아무리 Modfy 가 와도 픽업으로 안 잡히고 baseline 만 쌓인다. */
    private fun establishBaseline() {
        aggregator.observeLine("ItemChange@ Reset PageId=100")
    }

    private fun quantityOf(itemId: String): Int? =
        aggregator.state.value.runs.lastOrNull()?.items?.firstOrNull { it.itemId == itemId }?.quantity

    /**
     * `scripts/capture-log.sh pull <name>` 로 저장한 실기기 로그 조각을 그대로 재생한다.
     * baseline 은 fixture 안에 없을 수 있으므로(캡처가 이미 baseline 이후부터 시작) 필요하면
     * 호출 전에 [establishBaseline] 을 먼저 호출할 것.
     */
    private fun replayFixture(name: String) {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name.log")) {
            "fixture 없음: app/src/test/resources/fixtures/$name.log " +
                "(scripts/capture-log.sh pull $name 으로 생성)"
        }
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { aggregator.observeLine(it) }
        }
    }

    @Test
    fun `baseline gating - Modfy before bag sort is not counted`() {
        aggregator.observeLine("ItemChange@ Update Id=100300_uuid1 BagNum=5 in PageId=102 SlotId=0")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 102 SlotId = 0 ConfigBaseId = 100300 Num = 5")

        assertFalse(aggregator.state.value.baselineReady)
        assertTrue(aggregator.state.value.runs.isEmpty())
    }

    @Test
    fun `simple pickup after baseline records exact delta`() {
        establishBaseline()
        // 최초 baseline: 100개 보유.
        aggregator.observeLine("BagMgr@:InitBagData PageId = 102 SlotId = 5 ConfigBaseId = 100300 Num = 100")

        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Update Id=100300_uuidA BagNum=103 in PageId=102 SlotId=5")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 102 SlotId = 5 ConfigBaseId = 100300 Num = 103")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")

        // baseline 100 + 실거래 uuid 로 이어받았으므로 델타는 +3 이어야 한다 (103 전체가 아니라).
        assertEquals(3, quantityOf("100300"))
    }

    /**
     * 실측 버그 재현: 새 아이템을 스택으로(3개) 한 번에 처음 획득하면
     * 예전 코드는 무조건 1개로 클램프했다. 확정된 실제 변화(Add 바로 뒤 매칭 Modfy)라면
     * 전체 수량을 델타로 인정해야 한다.
     */
    @Test
    fun `first-time stacked pickup counts full amount, not clamped to 1`() {
        establishBaseline()

        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Add Id=6055_uuidNew BagNum=3 in PageId=103 SlotId=50")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 50 ConfigBaseId = 6055 Num = 3")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")

        assertEquals(3, quantityOf("6055"))
    }

    /** 소비(CONSUME) 컨텍스트에서 첫 sighting 은 여전히 안전하게 -1 로만 클램프한다. */
    @Test
    fun `first-time consume in unknown slot stays clamped to -1`() {
        establishBaseline()

        aggregator.observeLine("ItemChange@ ProtoName=Spv3Open start")
        aggregator.observeLine("ItemChange@ Update Id=400008_uuidC BagNum=7 in PageId=103 SlotId=9")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 9 ConfigBaseId = 400008 Num = 7")
        aggregator.observeLine("ItemChange@ ProtoName=Spv3Open end")

        // totalCountInSlot(7) 은 소비 "이후" 남은 값이라 델타로 못 쓴다 — 최소값 -1 만 인정.
        assertEquals(-1, quantityOf("400008"))
    }

    /**
     * 실측 버그 재현: baseline 때 합성 키(page:slot:itemId)로 잡힌 아이템이 이후 실거래에서
     * uuid 키로 들어오면, 예전 코드는 그 uuid 를 "처음 본다"고 오판해 baseline 전체를
     * 신규 획득으로 잡았다 (581개 보유 중 +3만 늘었는데 584 전체를 획득 처리).
     * 지금은 포지션 점유 키가 같은 아이템으로 바뀌면 기존 수량을 이어받아야 한다.
     */
    @Test
    fun `slot re-key to uuid form carries forward baseline count`() {
        establishBaseline()
        // baseline 이 이 포지션에 대한 Update-Add 를 못 보고 InitBagData 만 봐서
        // 합성 키(page:slot:itemId)로 잡히는 상황을 재현.
        aggregator.observeLine("BagMgr@:InitBagData PageId = 103 SlotId = 117 ConfigBaseId = 6002 Num = 581")

        // 이후 실거래 픽업은 항상 완전한 uuid 키로 들어온다.
        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Update Id=6002_7dc6b0af BagNum=584 in PageId=103 SlotId=117")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 117 ConfigBaseId = 6002 Num = 584")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")

        // 델타는 +3 이어야 한다 (584 전체가 아니라).
        assertEquals(3, quantityOf("6002"))
    }

    /**
     * 다른 아이템이 같은 포지션을 재사용하는 경우엔 이전 키의 보유량이 새 키로
     * 이어지면 안 되고(아이템이 다르므로), 옛 키는 무효화돼야 이중 집계가 안 된다.
     */
    @Test
    fun `different item reusing a position does not inherit old item's count`() {
        establishBaseline()
        aggregator.observeLine("BagMgr@:InitBagData PageId = 103 SlotId = 117 ConfigBaseId = 6002 Num = 581")

        // 같은 포지션(103:117)에 완전히 다른 아이템(9999)이 새로 배치.
        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Add Id=9999_uuidZ BagNum=2 in PageId=103 SlotId=117")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 117 ConfigBaseId = 9999 Num = 2")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")

        // 새 아이템은 정말 새 아이템이니 2개 그대로.
        assertEquals(2, quantityOf("9999"))
        aggregator.refreshHoldings()
        val holdings = aggregator.state.value.holdings
        // 옛 6002(581개)가 유령으로 남아 합산되면 안 된다 — 9999 만 있어야 한다.
        assertTrue(holdings.none { it.itemId == "6002" })
    }

    /** 경매장 등록(Delete) — 슬롯 전체가 사라지면 count=0 으로 확정, 보유량에서 빠진다. */
    @Test
    fun `full-stack delete zeroes the slot`() {
        establishBaseline()
        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Add Id=400008_uuidD BagNum=18 in PageId=103 SlotId=4")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 4 ConfigBaseId = 400008 Num = 18")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")
        assertEquals(18, quantityOf("400008"))

        aggregator.observeLine("ItemChange@ ProtoName=XchgForSale start")
        aggregator.observeLine("ItemChange@ Delete Id=400008_uuidD in PageId=103 SlotId=4")
        aggregator.observeLine("BagMgr@:RemoveBagItem PageId = 103 SlotId = 4")
        aggregator.observeLine("ItemChange@ ProtoName=XchgForSale end")

        aggregator.refreshHoldings()
        assertTrue(aggregator.state.value.holdings.none { it.itemId == "400008" })
    }

    @Test
    fun `entering exchange auto-pauses and exiting auto-resumes`() {
        establishBaseline()
        assertFalse(aggregator.state.value.paused)

        aggregator.observeLine(
            "TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Run "
        )
        assertTrue(aggregator.state.value.inExchange)
        assertTrue(aggregator.state.value.paused)

        aggregator.observeLine(
            "TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Destory "
        )
        assertFalse(aggregator.state.value.inExchange)
        assertFalse(aggregator.state.value.paused)
    }

    @Test
    fun `manual pause before entering exchange survives exiting`() {
        establishBaseline()
        aggregator.pauseSession()
        assertTrue(aggregator.state.value.paused)

        aggregator.observeLine(
            "TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Run "
        )
        assertTrue(aggregator.state.value.paused)

        aggregator.observeLine(
            "TipMsgShowMgr@DispatchPageRunChange PageName = AuctionHouseV2 , PageRunState = Destory "
        )
        // 유저가 직접 걸어둔 pause 는 거래소가 자동으로 풀면 안 된다.
        assertTrue(aggregator.state.value.paused)
    }

    @Test
    fun `pickups while paused update slot state but are not counted`() {
        establishBaseline()
        aggregator.pauseSession()

        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Add Id=6055_uuidP BagNum=5 in PageId=103 SlotId=60")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 60 ConfigBaseId = 6055 Num = 5")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")

        assertEquals(0.0, aggregator.state.value.totalValue, 0.0001)
        assertTrue(aggregator.state.value.runs.isEmpty())
    }

    /**
     * `scripts/capture-log.sh` 로 실기기에서 직접 캡처한 거래소 방문 구간 재생.
     * 캡처된 원본에는 진입(Run) 마커보다 **먼저** 낙오된 Destory 한 번이 더 있었다
     * (다른 팝업의 PageStack 에 AuctionHouseV2 가 부모로 걸려 있어서로 추정) —
     * exitExchange() 의 `if (!inExchange) return` 가드가 이런 걸 안전하게 무시하는지도 같이 검증.
     */
    @Test
    fun `replays a real exchange visit capture and ends outside the exchange`() {
        establishBaseline()
        replayFixture("exchange-visit")

        assertFalse(aggregator.state.value.inExchange)
        assertFalse(aggregator.state.value.paused)
    }

    @Test
    fun `holdings excludes items with no known name`() {
        establishBaseline()
        every { itemInfo.lookup("77777") } returns null

        aggregator.observeLine("ItemChange@ ProtoName=PickItems start")
        aggregator.observeLine("ItemChange@ Add Id=77777_uuidU BagNum=4 in PageId=103 SlotId=61")
        aggregator.observeLine("BagMgr@:Modfy BagItem PageId = 103 SlotId = 61 ConfigBaseId = 77777 Num = 4")
        aggregator.observeLine("ItemChange@ ProtoName=PickItems end")

        aggregator.refreshHoldings()
        assertTrue(aggregator.state.value.holdings.none { it.itemId == "77777" })
    }
}
