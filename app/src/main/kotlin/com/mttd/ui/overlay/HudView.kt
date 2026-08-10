package com.mttd.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.mttd.domain.models.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** 목록에 한 번에 보일 줄 수. 이보다 많으면 목록 안에서 스크롤. */
private const val VISIBLE_ROWS = 4
/** 한 줄 높이(아이콘 14 dp) + 줄 간격 2 dp. */
private val ROW_HEIGHT = 16.dp
private val MAX_LIST_HEIGHT = ROW_HEIGHT * VISIBLE_ROWS
/** 거래소 화면(보유 아이템 목록)에서는 다른 통계가 없으니 더 많이 보여준다. */
private val HOLDINGS_LIST_HEIGHT = ROW_HEIGHT * 10

@Composable
fun HudOverlay(
    sessionState: StateFlow<SessionState>,
    priceState: StateFlow<com.mttd.data.prices.PriceRepository.State>? = null,
    onCollapse: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onRefreshHoldings: () -> Unit = {},
) {
    val session by sessionState.collectAsStateWithLifecycle()
    val prices = priceState?.collectAsStateWithLifecycle()?.value

    // 시간이 실제로 흐를 때만 1 초 틱을 돌린다 (일시정지·집계 대기 중엔 정지).
    val ticking = session.active && !session.paused && session.baselineReady
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) {
        while (ticking) { delay(1000); tick++ }
    }
    val elapsed = remember(session.startedAtMs, session.active, session.endedAtMs, session.paused, session.pausedAccumulatedMs, session.pausedSinceMs, tick) {
        session.elapsedMs
    }
    val incomePerHour = remember(session.totalValue, session.active, session.endedAtMs, session.paused, tick) {
        session.incomePerHour
    }
    val netIncomePerHour = remember(session.netTotalValue, session.active, session.endedAtMs, session.paused, tick) {
        session.netIncomePerHour
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE2E8F0).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("mTTD", color = Color(0xFFFB923C), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                val statusText = when {
                    !session.active -> "대기"
                    session.paused -> "❚❚ 일시정지"
                    else -> "● 진행"
                }
                Text(statusText, color = Color(0xFFCBD5E1), fontSize = 10.sp)
                Spacer(Modifier.fillMaxWidth().weight(1f))
                HudMaterialIconButton(Icons.Filled.Settings, onOpenSettings)
                Spacer(Modifier.width(3.dp))
                if (session.inExchange) {
                    // 거래소 안에서는 pause 가 자동 제어라 수동 토글 버튼이 필요 없고,
                    // 새로고침은 리셋이 아니라 보유 아이템 가치 재계산이어야 한다.
                    HudMaterialIconButton(Icons.Filled.Refresh, onRefreshHoldings)
                } else {
                    // 리셋은 여기서 뺐다 — 오버레이는 작고 드래그 중에도 탭이 쉽게 튀어서
                    // 확인 없는 되돌릴 수 없는 동작을 두기엔 위험하다. 리셋은 앱 쪽(확인 팝업 있음)에서만.
                    HudMaterialIconButton(
                        if (session.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        onTogglePause,
                    )
                }
                Spacer(Modifier.width(3.dp))
                HudMaterialIconButton(Icons.Filled.Close, onCollapse)
            }

            if (session.inExchange) {
                HoldingsBody(session.holdings)
            } else {
                when {
                    prices != null && prices.loading -> Text(
                        "💰 시세 정보 업데이트 중...",
                        color = Color(0xFF60A5FA),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // <= 1 : 100300 override 하나만 있는 "빈 시즌" 상태도 시세 없음으로 취급
                    prices != null && prices.itemsWithPrice <= 1 -> Text(
                        "💰 시세 정보 없음",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    !session.baselineReady -> Text(
                        "🎒 게임에서 로그 오픈 후 가방 정렬을 눌러주세요",
                        color = Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HudStat("경과", formatElapsed(elapsed))
                HudStat("총 수익", formatFire(session.totalValue) + " (${formatFire(session.netTotalValue)} TAX)")
                HudStat("시간당", formatFire(incomePerHour) + "/h (${formatFire(netIncomePerHour)}/h TAX)")
                HudStat("맵 진입", "${session.mapsEntered}")

                Spacer(Modifier.height(1.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "이번 맵",
                        color = Color(0xFFCBD5E1),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    // 이번 맵 수익 합계 (목록이 상위 N 개로 잘려도 합계는 전체 기준)
                    Text(
                        formatFire(session.currentMapValue),
                        color = when {
                            session.currentMapValue < 0 -> Color(0xFFF87171)
                            session.currentMapValue > 0 -> Color(0xFF4ADE80)
                            else -> Color(0xFF94A3B8)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (session.recentPickups.isEmpty()) {
                    Text("(없음)", color = Color(0xFF94A3B8), fontSize = 10.sp)
                } else {
                    // 창 높이는 WRAP_CONTENT 라 목록이 짧으면 HUD 도 같이 작아진다.
                    // 길어질 때만 이 상한에서 멈추고 목록 안에서 스크롤.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = MAX_LIST_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (p in session.recentPickups) {
                            PickupRow(p)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 거래소 진입 시 수익 화면 대신 보이는 몸체 — 현재 보유 아이템을 가치순으로.
 * [SessionAggregator.enterExchange] 가 진입 시점에 [SessionState.holdings] 를 채운다.
 */
@Composable
private fun HoldingsBody(holdings: List<com.mttd.domain.models.PickupSummary>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "🏪 보유 아이템 가치",
            color = Color(0xFFFBBF24),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatFire(holdings.sumOf { it.value }),
            color = Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
    Spacer(Modifier.height(2.dp))
    if (holdings.isEmpty()) {
        Text("(보유 아이템 없음)", color = Color(0xFF94A3B8), fontSize = 10.sp)
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = HOLDINGS_LIST_HEIGHT)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (p in holdings) {
                PickupRow(p)
            }
        }
    }
}

/**
 * "이번 맵" 한 줄 — 아이콘 · 이름 · 수량 · 가치.
 *
 * 이름만 남은 폭을 먹고, 수량/가치는 오른쪽에 고정 폭으로 붙여 세로로 열이 맞게 한다.
 */
@Composable
private fun PickupRow(p: com.mttd.domain.models.PickupSummary) {
    val txtColor = when {
        p.value < 0 -> Color(0xFFF87171)  // 소비 = 빨강
        p.value > 0 -> Color(0xFFE2E8F0)  // 픽업 = 밝은 회백
        else -> Color(0xFFCBD5E1)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconRef = p.iconUrl
        if (!iconRef.isNullOrBlank()) {
            AsyncImage(
                model = iconRef,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            p.itemName ?: "Unknown",
            color = txtColor,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // 1 개 획득도 ×1 로 표시 (예전엔 quantity == 1 이면 아예 숨겨서 빠진 것처럼 보였다)
        Text(
            if (p.quantity != 0) "×${p.quantity}" else "",
            color = txtColor,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (p.value != 0.0) formatFire(p.value) else "-",
            color = txtColor,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 46.dp),
        )
    }
}

/**
 * Material Icons 기반 버튼. 이모지 렌더링 이슈 (⏸이 삼성 이모지 폰트에서 노랗게 뜨는 문제) 회피.
 */
@Composable
private fun HudMaterialIconButton(icon: ImageVector, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFF1F5F9),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun HudIconButton(
    symbol: String,
    onClick: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    bold: Boolean = false,
) {
    // Material TextButton 은 min touch target 48dp 강제 + ripple 색이 테마 dynamic color 따라
    // 노란 계열로 나올 수 있음. Box + clickable(no ripple) 로 대체해서 딱 24dp, 배경 없음 유지.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = Color(0xFFF1F5F9),
            fontSize = fontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun HudStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            color = Color(0xFFF1F5F9),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

internal fun formatElapsed(ms: Long): String {
    if (ms <= 0) return "0s"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, sec)
        else -> "%d:%02d".format(m, sec)
    }
}

/** 게임 통화 표시. 매우 작은 값(0.01 이하)부터 큰 값(수백만) 까지 대응. */
internal fun formatFire(v: Double): String {
    val abs = kotlin.math.abs(v)
    return when {
        abs == 0.0 -> "0"
        abs < 1.0 -> "%.4f".format(v)
        abs < 100.0 -> "%.2f".format(v)
        abs < 10_000.0 -> "%,.0f".format(v)
        abs < 1_000_000.0 -> "%.1fk".format(v / 1_000.0)
        else -> "%.2fM".format(v / 1_000_000.0)
    }
}
