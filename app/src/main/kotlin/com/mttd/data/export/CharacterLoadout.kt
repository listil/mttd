package com.mttd.data.export

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * mini-tlidb "MLI1" 로그 연동 스펙 (https://mini-tlidb.winterer.workers.dev/logimport_spec)
 * 의 페이로드 JSON 구조. 필드 순서가 [Mli1Codec] 테스트의 스펙 테스트 벡터와 바이트 단위로
 * 맞아야 하므로, 필드를 임의로 재배열하지 말 것 (kotlinx.serialization 은 선언 순서대로 직렬화한다).
 *
 * 스펙은 v1 내에서 필드 "추가"만 허용하고, 수신측은 모르는 키를 조용히 무시한다.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CharacterLoadout(
    /** 페이로드 버전. 스펙상 필수 필드라 기본값(1)이어도 항상 직렬화되어야 한다. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val v: Int = 1,
    /** 캐릭터(프리셋) 이름으로 쓰인다. */
    val char: String? = null,
    val hero: String? = null,
    val heroPur: String? = null,
    /** 송신 앱 식별자, 16자 이내. */
    val src: String? = null,
    /** 슬롯ID(1~5=액티브, N01~N05=보조, 1001+=패시브) → 스킬 ConfigBaseId. */
    val skills: Map<String, Int>? = null,
    val gear: List<LoadoutGear>? = null,
    /** 신격 석판 재능 노드 ID (중복 허용). */
    val slate: List<String>? = null,
    val mems: List<LoadoutMemory>? = null,
    /**
     * 석판 인스턴스별 상세(출처 아이템·보드 배치) — 있으면 수신 측이 [slate] 대신 이걸 써서
     * 출처 아이템·보드 배치까지 복원한다. 구버전 수신측 호환을 위해 [slate] 도 계속 같이 보낸다.
     */
    val sb: List<LoadoutSlateBlock>? = null,
    /**
     * 배치된 핵심 재능. 캐릭터가 career(전직) 를 4개까지 동시에 활성화할 수 있고
     * (`player.GeniusCareerIdStr`) career 마다 `bonusGeniusInfo` 가 따로 있는데, 실기기 대조
     * (2026-08-13, 인게임 "핵심 재능" 화면에 5개 표시) 결과 4개 career 의 `bonusGeniusInfo` 를
     * 전부 합친 게 정확히 그 5개와 개수가 일치했다 — "현재 career" 를 골라야 하는 게 아니라
     * 전체 career 를 합치면 된다.
     */
    val genius: LoadoutGenius? = null,
    /**
     * 운명 휠에 소켓한 천명·숙명의 게임 id (덤프 `tattoInfo` 의 `BaseId`, 350xxx대역). 같은 걸
     * 여러 개 꽂았으면 스펙대로 그대로 중복해서 담는다 — dedup 하지 않는다.
     */
    val dst: List<String>? = null,
    /** 재능 노드에 소켓된 제노 프리즘 — 여러 career 를 통틀어 첫 번째로 발견된 것 하나만 담는다. */
    val prism: LoadoutPrism? = null,
    /**
     * 스펙에 없는 필드 — 로드아웃이 안 바뀌면 [Mli1Codec.encode] 가 매번 바이트 단위로 동일한
     * `logimport=` 값을 내는데, 실기기에서 그 값으로 크롬을 반복 열면(같은 값이 아니라 아예
     * Custom Tabs 로 매번 새 액티비티를 띄워도) 두 번째부터 흰 화면 무한 로딩으로 막혔다 — URL에
     * fragment 만 다르게 붙여도 안 고쳐졌던 걸로 봐서 실제 페이로드(`logimport` 쿼리 값) 자체가
     * 매번 달라야 한다는 뜻으로 보고, [OnboardingScreen.LoadoutExportCard] 에서 내보낼 때마다
     * 이 필드에 난수를 채워 압축 결과를 매번 다르게 만든다. 스펙 문서에 "v1 내 필드 추가만 허용,
     * 수신측은 모르는 키를 조용히 무시" 라고 명시돼 있어 안전하다.
     */
    val nonce: String? = null,
)

/** 장비 슬롯: 1=투구, 2=무기, 3=방패, 4=갑옷, 5=목걸이, 6~7=반지, 8=벨트, 9=장갑, 10=신발. */
@Serializable
data class LoadoutGear(
    val slot: Int,
    val base: Int,
    val uniq: Int,
    val baseAffix: List<String>? = null,
    val prefix: List<String>? = null,
    val suffix: List<String>? = null,
    val chip: List<String>? = null,
    val enchant: List<String>? = null,
)

/** 히어로 추억. base 값: 71001=근원, 71002=자기 수호, 71003=진격. */
@Serializable
data class LoadoutMemory(
    val base: Int,
    val baseAffix: List<String>? = null,
    val prefix: List<String>? = null,
    val suffix: List<String>? = null,
)

/** 배치된 핵심 재능 ID 목록(최대 5개, career 4개 합산). */
@Serializable
data class LoadoutGenius(
    val core: List<String>? = null,
)

/** 제노 프리즘. `slots` 는 옵션 3칸 고정 — 빈 칸은 null. */
@Serializable
data class LoadoutPrism(
    /** 프리즘이 소켓된 재능 노드 id. */
    val gid: String,
    /** 프리즘 게임 id, 360xxx대역. */
    val base: String,
    val slots: List<String?>? = null,
)

/**
 * 석판 인스턴스 하나의 배치 정보. 스펙상 이름 있는 JSON 객체가 아니라
 * `[uniq, index, direction, nodes]` 순서의 raw 배열로 직렬화돼야 해서([LoadoutSlateBlockSerializer]
 * 참조) 필드 이름 자체는 페이로드에 안 실리고 순서만 중요하다 — 필드 순서를 바꾸면 스펙과 안 맞다.
 */
@Serializable(with = LoadoutSlateBlockSerializer::class)
data class LoadoutSlateBlock(
    /** 석판 인스턴스 id. 값이 없는 석판(신격 석판 등, `UniqueItmId=0`)은 대신 `BaseId` 를 담는다. */
    val uniq: Int,
    /** 보드 위치, 행×10+열 (1-based). */
    val index: Int,
    /** 반시계 90도 회전 횟수 (0~3). 없으면 0. */
    val direction: Int,
    /** 이 석판이 부여하는 재능 노드 id 목록. */
    val nodes: List<String>,
)

/** [LoadoutSlateBlock] 을 이름 있는 객체가 아니라 `[uniq, index, direction, nodes]` 배열로 직렬화. */
object LoadoutSlateBlockSerializer : KSerializer<LoadoutSlateBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LoadoutSlateBlock")

    override fun serialize(encoder: Encoder, value: LoadoutSlateBlock) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("LoadoutSlateBlock는 JSON 인코더에서만 직렬화 가능")
        jsonEncoder.encodeJsonElement(
            buildJsonArray {
                add(JsonPrimitive(value.uniq))
                add(JsonPrimitive(value.index))
                add(JsonPrimitive(value.direction))
                add(buildJsonArray { value.nodes.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }

    override fun deserialize(decoder: Decoder): LoadoutSlateBlock {
        val jsonDecoder = decoder as? JsonDecoder ?: error("LoadoutSlateBlock는 JSON 디코더에서만 역직렬화 가능")
        val arr: JsonArray = jsonDecoder.decodeJsonElement().jsonArray
        return LoadoutSlateBlock(
            uniq = arr[0].jsonPrimitive.int,
            index = arr[1].jsonPrimitive.int,
            direction = arr[2].jsonPrimitive.int,
            nodes = arr[3].jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
