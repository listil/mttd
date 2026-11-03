package com.mttd.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 오아시스 약탈 파견("군주 보내기") 감지 → 대상별 30분 쿨다운 타이머.
 *
 * 로그엔 어느 대상을 보냈는지가 안 찍혀서, 대상 4개를 "파견 순서대로 채우는 슬롯 4칸"으로 다룬다
 * — 대상마다 쿨다운이 따로 돌기 때문에 파견 1건 = 비어 있는(또는 쿨다운이 끝난) 칸 하나를 30분 잠금.
 *
 * 파견 1건 확정 신호 (2026-10-31 태블릿 로그, PC 로그도 동일):
 *   PageName = S10GamePlayPillage , PageRunState = Run      ← 약탈 화면
 *   UIButton@ normal-click UIButton_RobMul                  ← 파견 버튼
 *   (~50 ms) PushMgr:RecvPushCombineMsg ProtoId = 5583
 *   (~60 ms) PageName = S10GamePlayPillage , PageRunState = Destory
 * 클릭 뒤 [CONFIRM_WINDOW_MS] 안에 화면이 닫혀야 파견으로 본다 — 실패한 클릭(같은 로그 13:15:40)은
 * 응답도 없고 화면도 그대로였다. ProtoId 5583 은 모바일에선 아무것도 안 해도 30초마다 저절로
 * 오므로 확정 신호로 쓰지 않는다.
 *
 * 앱을 켜기 전에 보낸 파견은 알 수 없다(로그를 끝에서부터 읽으므로) — 의도된 제한.
 *
 * 수령: 30분 뒤 돌아온 약탈품은 공항의 보상함(S10RewardInventory)에서 "받기"(BtnTakeFilter)로
 * 꺼낸다. 보상함은 대상 구분 없이 쌓이고(같은 날 08:32~08:34, 대상 4개에 보상함 10번 열림 —
 * 약탈 이벤트 전투 보상도 섞임) 어느 대상 것인지 안 찍히므로, 받기 1번이면 준비된 칸 전부를
 * "안 보냄"으로 되돌린다. 아직 쿨다운 중인 칸은 그대로.
 *
 * 받기 줄을 못 보는 경우(PC 에서 수령, 폴러가 끊긴 사이 수령 — 재연결 시 EOF 부터 읽는다)엔
 * 준비 칸이 영영 안 꺼지므로 두 가지 탈출구를 둔다: HUD 의 군주 줄을 탭하면 [clearReady],
 * 준비된 지 [READY_EXPIRE_MS] 가 지나면 [expireStale] 로 저절로 "안 보냄".
 */
class PlunderDispatchTracker(
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 슬롯별 쿨다운 종료 시각(epoch ms). null = 이번 세션에 아직 안 보낸 칸. */
    data class State(val endsAtMs: List<Long?> = List(SLOT_COUNT) { null }) {
        val anyDispatched: Boolean get() = endsAtMs.any { it != null }
        fun readyCount(nowMs: Long): Int = endsAtMs.count { it != null && it <= nowMs }
        fun runningCount(nowMs: Long): Int = endsAtMs.count { it != null && it > nowMs }

        /** 다음에 상태가 바뀌는 시각 — 쿨다운 끝(준비) 또는 준비 칸 자동 만료. 없으면 null. */
        fun nextChangeAtMs(nowMs: Long): Long? = endsAtMs.filterNotNull()
            .map { if (it > nowMs) it else it + READY_EXPIRE_MS }
            .minOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pageOpen = false
    private var pendingClickLogMs: Long? = null
    private var rewardOpen = false

    fun observeLine(line: String) {
        // 핫패스 — 이 토큰들이 없는 줄(대부분)은 바로 버린다.
        if (!line.contains("S10GamePlayPillage") && !line.contains("UIButton_RobMul") &&
            !line.contains("S10RewardInventory") && !line.contains("BtnTakeFilter")
        ) return

        rewardPageRegex.find(line)?.let { m ->
            rewardOpen = m.groupValues[1] == "Run"
            return
        }
        if (rewardOpen && takeClickRegex.containsMatchIn(line)) {
            clearReady()
            return
        }

        pageRegex.find(line)?.let { m ->
            val run = m.groupValues[1] == "Run"
            if (!run) {
                val click = pendingClickLogMs
                val closedAt = parseLogMs(line)
                if (click != null && closedAt != null && closedAt - click in 0..CONFIRM_WINDOW_MS) {
                    recordDispatch()
                }
                pendingClickLogMs = null
            }
            pageOpen = run
            return
        }
        if (pageOpen && clickRegex.containsMatchIn(line)) {
            // 실패한 클릭 뒤 다시 누르면 마지막 클릭 기준으로 다시 잰다.
            pendingClickLogMs = parseLogMs(line)
        }
    }

    private fun recordDispatch() {
        val t = now()
        val slots = _state.value.endsAtMs.toMutableList()
        val free = slots.indexOfFirst { it == null || it <= t }
        // 4칸이 다 쿨다운 중인데 또 보냈다면(감지 누락 등) 가장 먼저 끝나는 칸을 덮어쓴다.
        val idx = if (free >= 0) free else slots.indices.minBy { slots[it]!! }
        slots[idx] = t + COOLDOWN_MS
        _state.value = State(slots)
    }

    /** 준비된 칸을 전부 "안 보냄"으로. 보상함 받기 감지 + HUD 군주 줄 탭. 쿨다운 중인 칸은 그대로. */
    fun clearReady() {
        val t = now()
        val slots = _state.value.endsAtMs
        if (slots.none { it != null && it <= t }) return
        _state.value = State(slots.map { if (it != null && it <= t) null else it })
    }

    /** 준비된 지 [READY_EXPIRE_MS] 넘은 칸을 "안 보냄"으로. 바뀐 게 있으면 true. */
    fun expireStale(): Boolean {
        val t = now()
        val slots = _state.value.endsAtMs
        if (slots.none { it != null && it + READY_EXPIRE_MS <= t }) return false
        _state.value = State(slots.map { if (it != null && it + READY_EXPIRE_MS <= t) null else it })
        return true
    }

    /**
     * 설정의 "테스트 표시" — 실제 파견 없이 HUD/배지 모양을 확인하는 용도.
     * 4칸에 서로 다른 상태(25분 남음 / 10분 남음 / 준비 / 안 보냄)를 넣는다.
     */
    fun showDemo() {
        val t = now()
        _state.value = State(listOf(t + 25 * 60_000L, t + 10 * 60_000L, t - 1, null))
        pendingClickLogMs = null
    }

    /** 설정의 "타이머 초기화". */
    fun reset() {
        _state.value = State()
        pendingClickLogMs = null
        rewardOpen = false
    }

    private fun parseLogMs(line: String): Long? {
        val m = timestampRegex.find(line) ?: return null
        val (y, mo, d, h, mi, s, ms) = m.destructured
        return java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), ms.toInt() * 1_000_000)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }

    companion object {
        const val SLOT_COUNT = 4
        const val COOLDOWN_MS = 30L * 60 * 1000
        /** 실측 클릭→화면 닫힘 ~110 ms. 여유 있게 1초. */
        const val CONFIRM_WINDOW_MS = 1000L
        /** 준비 상태로 이만큼 지나도 받기가 안 보이면 놓친 걸로 보고 지운다. */
        const val READY_EXPIRE_MS = 60L * 60 * 1000

        // "S10GamePlayPillage , " — 뒤에 공백·쉼표까지 맞춰서 S10GamePlayPillageEventBattle(약탈 이벤트 전투) 은 안 걸리게.
        private val pageRegex = Regex("""PageName\s*=\s*S10GamePlayPillage\s*,\s*PageRunState\s*=\s*(\w+)""")
        // LogDataTable 경고 줄에도 "S10GamePlayPillage_C_UIButton_RobMul" 이 찍히므로 실제 클릭 줄만.
        private val clickRegex = Regex("""UIButton@\s+normal-click\s+UIButton_RobMul\b""")
        private val rewardPageRegex = Regex("""PageName\s*=\s*S10RewardInventory\s*,\s*PageRunState\s*=\s*(\w+)""")
        // 이것도 LogDataTable 경고 줄("S10RewardInventory_C_BtnTakeFilter")과 안 겹치게 실제 클릭 줄만.
        private val takeClickRegex = Regex("""UIButton@\s+normal-click\s+BtnTakeFilter\b""")
        private val timestampRegex = Regex("""^\[(\d{4})\.(\d{2})\.(\d{2})-(\d{2})\.(\d{2})\.(\d{2}):(\d{3})\]""")
    }
}
