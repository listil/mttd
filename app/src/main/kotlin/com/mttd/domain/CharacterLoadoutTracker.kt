package com.mttd.domain

import com.mttd.data.export.CharacterLoadout
import com.mttd.data.export.LoadoutGear
import com.mttd.data.export.LoadoutGenius
import com.mttd.data.export.LoadoutMemory
import com.mttd.data.export.LoadoutPrism
import com.mttd.data.log.TorchlightPayloadParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 캐릭터 장비/스킬/석판 스냅샷 추출기.
 *
 * [SessionAggregator] 와는 완전히 독립적인 관심사라 별도 클래스로 분리했다 — 세션/픽업 집계의
 * 델리케이트한 상태머신을 건드리지 않기 위함. `TrackerForegroundService` 의 라인 콜렉터가
 * `aggregator.observeLine(line)` 과 나란히 `observeLine(line)` 을 호출해주면 된다.
 *
 * ## 왜 장비는 `ItemChange@` 슬롯 델타(PageId=-1/-4)가 아니라 `GetPlayerData` 를 쓰는가
 *
 * 장비 착용/해제(`PutOn`/`TalentBlockEquip`)는 `ItemChange@` 로 델타만 찍히고, 세션 중 유저가
 * 실제로 슬롯을 건드리기 전까진 그 슬롯의 현재 상태(uuid, 어픽스)를 알 수 없다 — 로그인 시 한 번
 * 오는 전체 스냅샷을 놓치면 델타만으로는 "지금 전체 장비"를 재구성할 수 없다(실기기 캡처로 확인:
 * 마을 귀환/재접속으로는 이 페이지들이 재동기화되지 않고, 로그인 직후 딱 한 번만 옴). 게다가 델타
 * 라인에는 어픽스(prefix/suffix/baseAffix/chip/enchant) 정보가 전혀 없다. 스킬(`UnequipSkill`,
 * PageId=-6)은 사정이 달라 아래 별도 섹션에서 다룬다 — 어픽스가 없어도 되고, 로그인 시 이 페이지만
 * 예외적으로 전체가 델타로 리플레이된다.
 *
 * 반면 로그인 시 `----Socket RecvMessage STT----GetPlayerData----SynId=N----` 블록이 캐릭터
 * 전체 상태(장비 전 슬롯 + 전체 어픽스, 스킬 배치, 신격 석판 블록, 히어로 추억, HeroId 등)를 데스크톱과
 * 동일한 `+key [value]` 트리 포맷으로 통째로 내려준다 — 이미 있는 [TorchlightPayloadParser] 로
 * 바로 파싱 가능. 실기기 캡처(`relogin-capture` fixture, 2026-08-12)로 필드 경로를 전부 검증했다.
 *
 * ## 왜 `skills` 를 `skillLayout.slots` 가 아니라 `ItemChange@ PageId=-6` 델타로 만드는가
 *
 * `skillLayout.slots` 는 응답 하나당 정확히 30개로 잘려서 온다 — 실기기에서 서로 다른 8개의
 * `GetPlayerData` 응답(2026-08-12, 3시간+ 간격/여러 로그인)을 대조해보면 매번 겹치는 항목은
 * slot 번호까지 일치하지만 항상 다른 부분집합만 30개씩 실려 있었다. 처음엔 "응답마다 다른 30개를
 * 무작위로 잘라 보낸다"고 보고 여러 스냅샷을 누적(합집합)해서 30개 이상을 복구하려 했으나, 실기기
 * 재검증(2026-08-13, 재로그인 직후 캡처 `skill-full-relogin` fixture) 결과 이 가설의 전제 자체가
 * 틀렸다: **`GetPlayerData` 는 세션(로그인)당 딱 한 번만 오고, 맵 입장·마을 귀환으로는 재동기화되지
 * 않는다** — 즉 "같은 세션·무변경 상태에서 다른 30개가 온다"는 애초에 실기기에서 재현이 불가능한
 * 시나리오였고, 서로 다른 스냅샷 간 차이는 로그인 사이에 실제로 있었던 리스펙일 수 있다. 누적
 * 병합은 그런 stale(이미 해제된) 스킬을 세션 내내 계속 내보내는 부작용이 있었다.
 *
 * 대신 로그인 시 `GetPlayerData` 직후 `ItemChange@ Reset PageId=-6` 마커와 함께 스킬 슬롯 전체가
 * `ItemChange@ (Update|Add) Id=<baseId>_<uuid> BagNum=1 in PageId=-6 SlotId=<slot>` 델타로
 * **캡 없이** 통째로 리플레이된다(실측: 같은 로그인에서 `skillLayout.slots` 는 30개인데
 * `PageId=-6` 델타는 37개 — `skillLayout` 의 모든 항목이 이 37개의 부분집합이었다). 장착 해제는
 * `ItemChange@ Delete ... PageId=-6 SlotId=<slot>` 로 self-confirming하게 오고(일반 아이템의
 * `Update`/`Modfy` 페어링과 달리 확인 라인이 필요 없음 — `SessionAggregator.slotDeleteRegex` 와
 * 동일한 패턴), 세션
 * 중 실제 스킬 교체(`PutOn`/`UnequipSkill`)에도 실시간으로 같은 델타가 온다. 그래서 [skillSlots] 는
 * 이 델타를 그대로 반영하는 라이브 상태이고, `skillLayout.slots` (캡=30) 는 로그 유실 등으로
 * `PageId=-6` 리플레이가 끊겼을 때만 쓰이는 폴백(더 최신 값 우선)으로 격하했다. 캐릭터가 바뀌면
 * (`Reset PageId=-6` 재발생 또는 [player.Name]/[player.HeroId] 변경 감지) 상태를 리셋한다.
 *
 * 필드 매핑 (실측 근거는 [CharacterLoadoutTrackerTest] 의 fixture 참조):
 * - `char`/`hero`/`heroPur` = `player.Name`/`player.HeroId`/`player.HeroPurchaseId`
 * - `skills[slot]` = `ItemChange@ PageId=-6` 델타로 추적한 slot → skillId baseId (위 설명 참조,
 *   `skillLayout.slots` 는 이게 비어있을 때만 쓰는 캡=30 폴백)
 * - `gear` = `bag.wearItems[]` — slot=`Index`, base=`Item.BaseId`, uniq=`Item.UniqueItmId`,
 *   prefix/suffix=`Item.{Prefix,Suffix}Slots[].affixId`, baseAffix/chip=`Item.{BaseAffix,ChipAffix}[].Id`,
 *   enchant=`Item.EnchantSlots[].affixId`
 * - `slate` = `bag.dressingTalentBlocks[].Item.BaseTalentList[]` 전체를 평탄화 (블록 자체의
 *   BaseId 가 아니라 그 안에 실린 재능 노드 ID 목록 — 중복 허용)
 * - `mems` = `heroCharacterv3OverView.reminiscenceOverView.reminiscence[].detail` —
 *   base=`BaseId`, baseAffix=`BaseAffix[].Id`, suffix=`Suffix[].Id`
 * - `dst` = `ContractInfosV2.pointDatas[].tattoInfo.BaseId` (천명 소켓이 없는 포인트는
 *   `tattoInfo` 자체가 없어 자동으로 걸러진다). 실측(2026-08-12/13, 6~9개)으로는 스킬처럼 캡에
 *   걸리는 정황이 없어 skills 와 달리 스냅샷을 그대로 쓴다 — 매 로그인마다 통째로 교체.
 * - `genius.core` = `geniusInfos.info[].bonusGeniusInfo[].value.geniusId` 를 **career
 *   섹션 전부에 걸쳐 합산**한 것. 캐릭터가 career(전직)를 최대 4개까지 동시에 활성화할 수 있고
 *   (`player.GeniusCareerIdStr`, 예: `"6,63,62,44"`) career 마다 `bonusGeniusInfo` 가 따로
 *   붙는데, "현재 career" 를 가리키는 별도 플래그가 로그에 없다. 실기기 대조(2026-08-13, 인게임
 *   "핵심 재능" 화면에 5개 표시)로 4개 career 를 전부 합친 개수가 그 5개와 정확히 일치함을
 *   확인해 이 방식으로 정했다.
 * - `prism` = `geniusInfos.info[].prismInfo` 중 처음 발견된 것 하나(career 섹션의 직속 필드,
 *   재능 노드 안에 중첩된 게 아님) — `gid`=`prismInfo.geniusId`(소켓된 노드 id),
 *   `base`=`prismInfo.item.BaseId`, `slots`=`prismInfo.item.PrefixSlots[1..3].affixId`
 *   (빈 칸은 null, 스펙이 고정 3칸을 요구). 실측 캐릭터는 프리즘이 1개뿐이라 여러 개 소켓됐을 때
 *   실제로 몇 개까지 나오는지는 미검증.
 */
class CharacterLoadoutTracker {

    private val recvStartRegex = Regex("""RecvMessage\s+STT----GetPlayerData----SynId\s*=\s*\d+""")
    private val socketEndRegex = Regex("""----Socket\s+\w+Message\s+End----""")

    private val skillPage6ResetRegex = Regex("""ItemChange@ Reset PageId=-6""")
    private val skillPage6UpsertRegex =
        Regex("""ItemChange@ (?:Update|Add) Id=(\d+)_[0-9a-f-]+ BagNum=\d+ in PageId=-6 SlotId=(\S+)""")
    private val skillPage6DeleteRegex = Regex("""ItemChange@ Delete Id=\d+_[0-9a-f-]+ in PageId=-6 SlotId=(\S+)""")

    private var inBlock = false
    private val buffer = ArrayList<String>(8192)

    /** `ItemChange@ PageId=-6` 델타로 추적하는 라이브 스킬 슬롯 상태. 클래스 doc 참조 — 캡 없음. */
    private val skillSlots = LinkedHashMap<String, Int>()

    /** 가장 최근 `GetPlayerData` 의 `skillLayout.slots` (캡=30) — [skillSlots] 가 비었을 때만 쓰는 폴백. */
    private var skillLayoutFallback: Map<String, Int> = emptyMap()
    private var lastIdentity: Pair<String?, String?>? = null

    private val _loadout = MutableStateFlow<CharacterLoadout?>(null)
    /** 마지막으로 파싱에 성공한 스냅샷. null 이면 아직 `GetPlayerData` 를 한 번도 못 봤다는 뜻. */
    val loadout: StateFlow<CharacterLoadout?> = _loadout.asStateFlow()

    /** [loadout] 이 갱신된 시각 (epoch ms). UI 의 "n 분 전" 표시용이라 StateFlow 로 감쌀 정도는 아님. */
    var latestSyncAtMs: Long = 0
        private set

    val latestLoadout: CharacterLoadout? get() = _loadout.value

    fun observeLine(line: String) {
        if (line.contains("PageId=-6")) {
            handleSkillPage6Delta(line)
        }
        if (!inBlock) {
            if (line.contains("GetPlayerData") && recvStartRegex.containsMatchIn(line)) {
                inBlock = true
                buffer.clear()
            }
            return
        }
        if (line.contains("----Socket") && socketEndRegex.containsMatchIn(line)) {
            inBlock = false
            runCatching { extractLoadout(TorchlightPayloadParser.parse(buffer)) }
                .getOrNull()
                ?.let { fresh ->
                    val identity = fresh.char to fresh.hero
                    if (lastIdentity != null && lastIdentity != identity) {
                        skillSlots.clear()
                    }
                    lastIdentity = identity
                    skillLayoutFallback = fresh.skills.orEmpty()
                    _loadout.value = fresh.copy(skills = mergedSkills())
                    latestSyncAtMs = System.currentTimeMillis()
                }
            buffer.clear()
            return
        }
        buffer += line
    }

    /** 스킬 캡을 우회하는 `PageId=-6` 델타 처리. 클래스 doc의 "왜 PageId=-6 델타로 만드는가" 참조. */
    private fun handleSkillPage6Delta(line: String) {
        if (skillPage6ResetRegex.containsMatchIn(line)) {
            skillSlots.clear()
            _loadout.value = _loadout.value?.copy(skills = mergedSkills())
            return
        }
        skillPage6UpsertRegex.find(line)?.let { m ->
            val (base, slot) = m.destructured
            skillSlots[slot] = base.toInt()
            _loadout.value = _loadout.value?.copy(skills = mergedSkills())
            return
        }
        skillPage6DeleteRegex.find(line)?.let { m ->
            skillSlots.remove(m.groupValues[1])
            _loadout.value = _loadout.value?.copy(skills = mergedSkills())
        }
    }

    /** `skillSlots`(라이브, 캡 없음)를 우선하고 `skillLayoutFallback`(캡=30)으로 빈틈을 채운다. */
    private fun mergedSkills(): Map<String, Int>? {
        if (skillSlots.isEmpty()) return skillLayoutFallback.takeIf { it.isNotEmpty() }
        val merged = LinkedHashMap(skillLayoutFallback)
        merged.putAll(skillSlots)
        return merged
    }

    private fun extractLoadout(root: Map<String, Any>): CharacterLoadout? {
        val player = root.child("player") ?: return null
        return CharacterLoadout(
            char = player.leaf("Name"),
            hero = player.leaf("HeroId"),
            heroPur = player.leaf("HeroPurchaseId"),
            skills = extractSkills(root),
            gear = extractGear(root),
            slate = extractSlate(root),
            mems = extractMems(root),
            genius = extractGenius(root),
            dst = extractDst(root),
            prism = extractPrism(root),
        )
    }

    private fun careerSections(root: Map<String, Any>): List<Map<String, Any>> =
        root.child("geniusInfos")?.child("info")?.values?.mapNotNull { it.asMap() } ?: emptyList()

    private fun extractGenius(root: Map<String, Any>): LoadoutGenius? {
        val core = careerSections(root)
            .flatMap { career ->
                career.child("bonusGeniusInfo")?.values
                    ?.mapNotNull { it.asMap()?.child("value")?.leaf("geniusId") }
                    ?: emptyList()
            }
            .takeIf { it.isNotEmpty() }
            ?: return null
        return LoadoutGenius(core = core)
    }

    private fun extractPrism(root: Map<String, Any>): LoadoutPrism? {
        for (career in careerSections(root)) {
            val prismInfo = career.child("prismInfo") ?: continue
            val item = prismInfo.child("item") ?: continue
            val gid = prismInfo.leaf("geniusId") ?: continue
            val base = item.leaf("BaseId") ?: continue
            val prefixSlots = item.child("PrefixSlots")
            val slots = (1..3).map { i -> prefixSlots?.child(i.toString())?.leaf("affixId") }
            return LoadoutPrism(gid = gid, base = base, slots = slots)
        }
        return null
    }

    private fun extractSkills(root: Map<String, Any>): Map<String, Int>? =
        root.child("skillLayout")?.child("slots")?.values
            ?.mapNotNull { it.asMap() }
            ?.mapNotNull { entry ->
                val slotKey = entry.leaf("key") ?: return@mapNotNull null
                val skillId = entry.child("value")?.leaf("skillId") ?: return@mapNotNull null
                val baseId = skillId.substringBefore('_').toIntOrNull() ?: return@mapNotNull null
                slotKey to baseId
            }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }

    private fun extractGear(root: Map<String, Any>): List<LoadoutGear>? =
        root.child("bag")?.child("wearItems")?.values
            ?.mapNotNull { it.asMap()?.toLoadoutGear() }
            ?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any>.toLoadoutGear(): LoadoutGear? {
        val item = child("Item") ?: return null
        val base = item.leaf("BaseId")?.toIntOrNull() ?: return null
        val slot = leaf("Index")?.toIntOrNull() ?: return null
        return LoadoutGear(
            slot = slot,
            base = base,
            uniq = item.leaf("UniqueItmId")?.toIntOrNull() ?: 0,
            baseAffix = item.child("BaseAffix").affixIds("Id"),
            prefix = item.child("PrefixSlots").affixIds("affixId"),
            suffix = item.child("SuffixSlots").affixIds("affixId"),
            chip = item.child("ChipAffix").affixIds("Id"),
            enchant = item.child("EnchantSlots").affixIds("affixId"),
        )
    }

    private fun extractSlate(root: Map<String, Any>): List<String>? =
        root.child("bag")?.child("dressingTalentBlocks")?.values
            ?.mapNotNull { it.asMap() }
            ?.flatMap { entry ->
                entry.child("Item")?.child("BaseTalentList")?.values
                    ?.mapNotNull { it as? String }
                    ?: emptyList()
            }
            ?.takeIf { it.isNotEmpty() }

    private fun extractMems(root: Map<String, Any>): List<LoadoutMemory>? =
        root.child("heroCharacterv3OverView")?.child("reminiscenceOverView")?.child("reminiscence")?.values
            ?.mapNotNull { it.asMap()?.child("detail")?.toLoadoutMemory() }
            ?.takeIf { it.isNotEmpty() }

    private fun extractDst(root: Map<String, Any>): List<String>? =
        root.child("ContractInfosV2")?.child("pointDatas")?.values
            ?.mapNotNull { it.asMap()?.child("tattoInfo")?.leaf("BaseId") }
            ?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any>.toLoadoutMemory(): LoadoutMemory? {
        val base = leaf("BaseId")?.toIntOrNull() ?: return null
        return LoadoutMemory(
            base = base,
            baseAffix = child("BaseAffix").affixIds("Id"),
            suffix = child("Suffix").affixIds("Id"),
        )
    }

    /** `+<container>+N+<field> [value]` 형태로 반복되는 어픽스 목록에서 `field` 값만 뽑는다. */
    private fun Map<String, Any>?.affixIds(field: String): List<String>? =
        this?.values?.mapNotNull { it.asMap()?.leaf(field) }?.takeIf { it.isNotEmpty() }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any>? = this as? Map<String, Any>

    private fun Map<String, Any>.child(key: String): Map<String, Any>? = this[key].asMap()

    private fun Map<String, Any>.leaf(key: String): String? = this[key] as? String
}
