package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.domain.models.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 배지 2번째 줄에 표시할 수익 지표. 설정 탭에서 선택 가능 (카운트 계열은 후보에서 제외 —
 * 수익 0 일 때의 폴백으로만 쓰인다).
 *
 * [INCOME_PER_HOUR]/[NET_INCOME_PER_HOUR] 는 고정된 시간 기준이 아니라, 설정 탭의
 * "경과·시간당 수익 기준"([com.mttd.domain.models.TimeBasis]) 을 그대로 따라간다 — 이 값은
 * 수익 탭 헤드라인, 플로팅 HUD, 배지가 전부 공유하는 단일 설정이라 어디서 바꾸든 셋 다 같이
 * 바뀐다(예전엔 배지 1번째 줄만 따로 선택할 수 있었는데, 그러면 설정 탭에서 M/T 를 바꿔도
 * 플로팅 HUD 는 안 바뀌는 것처럼 보이는 혼란이 있어서 하나로 합침).
 */
enum class BadgeIncomeMetric(val id: String, val label: String, val perHour: Boolean) {
    INCOME_PER_HOUR("income_per_hour", "M/T 시간당 수익", perHour = true),
    NET_INCOME_PER_HOUR("net_income_per_hour", "M/T 시간당 실수령 (TAX 제외)", perHour = true),
    TOTAL_VALUE("total_value", "누적 총수익", perHour = false),
    NET_TOTAL_VALUE("net_total_value", "누적 실수령 (TAX 제외)", perHour = false),
    CURRENT_MAP_VALUE("current_map_value", "이번 맵 수익", perHour = false),
    ;

    fun value(session: SessionState, timeBasis: com.mttd.domain.models.TimeBasis): Double = when (this) {
        INCOME_PER_HOUR -> timeBasis.incomePerHour(session)
        NET_INCOME_PER_HOUR -> timeBasis.netIncomePerHour(session)
        TOTAL_VALUE -> session.totalValue
        NET_TOTAL_VALUE -> session.netTotalValue
        CURRENT_MAP_VALUE -> session.currentMapValue
    }

    companion object {
        val DEFAULT = INCOME_PER_HOUR
        fun fromId(id: String): BadgeIncomeMetric = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 최소화 뷰. 경과 시간 + 수익 지표 ([metricFlow] 로 선택, 기본은 시간당 수익).
 *
 * 1번째 줄(경과 시간)은 [timeBasisFlow] — 설정 탭 "경과·시간당 수익 기준"과 공유하는 값이라
 * 수익 탭/플로팅 HUD와 항상 같은 기준을 보여준다.
 */
@Composable
fun IconOverlay(
    sessionState: StateFlow<SessionState>,
    metricFlow: Flow<String> = flowOf(BadgeIncomeMetric.DEFAULT.id),
    timeBasisFlow: Flow<String> = flowOf(com.mttd.domain.models.TimeBasis.DEFAULT.id),
) {
    val session by sessionState.collectAsStateWithLifecycle()
    val metricId by metricFlow.collectAsStateWithLifecycle(initialValue = BadgeIncomeMetric.DEFAULT.id)
    val metric = remember(metricId) { BadgeIncomeMetric.fromId(metricId) }
    val timeBasisId by timeBasisFlow.collectAsStateWithLifecycle(
        initialValue = com.mttd.domain.models.TimeBasis.DEFAULT.id,
    )
    val timeBasis = remember(timeBasisId) { com.mttd.domain.models.TimeBasis.fromId(timeBasisId) }

    // 경과 시간을 흘려보내기 위한 1 초 틱.
    // 예전엔 `while (true)` 라 일시정지·집계 대기 상태에서도 영원히 깨어나
    // 오버레이를 매초 재구성/재드로우했다. 실제로 시간이 흐를 때만 돌린다.
    val ticking = session.active && !session.paused && session.baselineReady
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) {
        while (ticking) { delay(1000); tick++ }
    }

    val elapsed = remember(session.startedAtMs, session.active, session.endedAtMs, session.paused, session.runs, timeBasis, tick) {
        timeBasis.elapsedMs(session)
    }
    val income = remember(
        session.totalValue, session.netTotalValue, session.currentMapValue, session.runs,
        session.active, session.endedAtMs, session.paused, tick, metric, timeBasis,
    ) {
        metric.value(session, timeBasis)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A).copy(alpha = 0.85f), CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val primaryColor = when {
                session.paused -> Color(0xFFFBBF24)
                elapsed > 0 -> Color(0xFF4ADE80)
                else -> Color(0xFFCBD5E1)
            }
            val label = when {
                session.paused -> "❚❚"
                elapsed > 0 -> formatElapsedIcon(elapsed)
                else -> "대기"
            }
            Text(
                label,
                color = primaryColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            run {
                val incomeText = formatFire(income) + if (metric.perHour) "/h" else ""
                // 결정 아이콘을 빼고 그 공간만큼 숫자를 키운다. 그래도 자릿수가 아주 많으면
                // (5자리 이상 시간당 수익 등) 줄여서 "/h" 가 잘리지 않게 한다.
                val incomeFontSize = if (incomeText.length > 8) 9.sp else 12.sp
                Text(
                    incomeText,
                    color = if (session.paused) Color(0xFF94A3B8) else Color(0xFFFB923C),
                    fontSize = incomeFontSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 아이콘용 짧은 경과 포맷: 1h 미만은 mm:ss, 이상은 h:mm. */
private fun formatElapsedIcon(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d".format(h, m)
        else -> "%d:%02d".format(m, sec)
    }
}
