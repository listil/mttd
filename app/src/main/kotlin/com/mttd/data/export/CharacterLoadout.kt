package com.mttd.data.export

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

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
