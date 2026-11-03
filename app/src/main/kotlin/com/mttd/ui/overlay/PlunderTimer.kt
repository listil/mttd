package com.mttd.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mttd.domain.PlunderDispatchTracker
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** 약탈("군주") 타이머를 HUD 에 4칸으로 따로 보여줄지, 1개로 합쳐 보여줄지. */
enum class PlunderDisplayMode(val id: String, val label: String) {
    SEPARATE("separate", "4개 따로"),
    COMBINED("combined", "1개로 합쳐서");

    companion object {
        val DEFAULT = SEPARATE
        fun fromId(id: String): PlunderDisplayMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

private const val SEGMENTS = 6
private val SEGMENT_MS = PlunderDispatchTracker.COOLDOWN_MS / SEGMENTS   // 5분
private val RUNNING = Color(0xFFFBBF24)
private val READY = Color(0xFF4ADE80)
private val DIM = Color.White.copy(alpha = 0.18f)

/**
 * 30초마다 갱신되는 현재 시각. 칸이 5분 단위로만 바뀌어서 1초 틱은 필요 없다 — 배터리 부담을 줄인다.
 * [key] 가 바뀌면(새 파견) 즉시 다시 잰다.
 */
@Composable
private fun rememberCoarseNow(key: Any?): Long {
    val now by produceState(System.currentTimeMillis(), key) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    return now
}

/**
 * 슬롯 1칸 아이콘.
 * - endsAtMs == null: 이번 세션에 안 보낸 칸 — 흐린 테두리
 * - 진행 중: 6칸(5분씩) 링, 12시부터 시계 방향으로 남은 칸만 채워짐 → 5분마다 한 칸씩 꺼짐
 * - 끝남: 초록 원 + 체크
 */
@Composable
fun PlunderSlotIcon(endsAtMs: Long?, now: Long, size: Dp = 16.dp) {
    val done = endsAtMs != null && now >= endsAtMs
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.2f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            when {
                endsAtMs == null -> drawCircle(DIM, radius = this.size.minDimension / 2 - inset, style = Stroke(stroke / 2))
                done -> drawCircle(READY)
                else -> {
                    val remain = (endsAtMs - now).coerceAtLeast(0)
                    val filled = ceil(remain / SEGMENT_MS.toDouble()).toInt().coerceIn(1, SEGMENTS)
                    val step = 360f / SEGMENTS
                    val gap = 8f
                    for (i in 0 until SEGMENTS) {
                        drawArc(
                            color = if (i < filled) RUNNING else DIM,
                            startAngle = -90f + i * step + gap / 2,
                            sweepAngle = step - gap,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Butt),
                        )
                    }
                }
            }
        }
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = "약탈 준비", tint = Color(0xFF0F172A), modifier = Modifier.size(size * 0.75f))
        }
    }
}

/**
 * HUD 의 약탈 타이머 줄. 이번 세션에 파견이 한 번도 감지되지 않았으면 아무것도 안 그린다.
 * [mode] 가 [PlunderDisplayMode.COMBINED] 면 아이콘 1개: 준비된 칸이 하나라도 있으면 체크,
 * 아니면 가장 먼저 끝나는 칸의 링. 안 보낸 칸은 그리지 않는다(칸 순서는 대상과 무관해서 자리를
 * 지킬 이유가 없다) — 받기/탭/자동 만료로 비워진 칸은 그냥 사라진다.
 *
 * 준비된 칸이 있을 때 줄을 짧게 탭하면 [onClearReady] — 받기를 감지 못해 초록이 남은 경우 수동으로
 * 지운다. 이 줄은 HUD 의 스와이프(페이지 전환)·길게 눌러 드래그(창 이동) 영역 안에 있어서
 * `clickable` 대신 직접 구현: down 을 consume 하지 않아 부모 제스처를 막지 않고, 부모가 드래그/
 * 스와이프로 이동을 consume 하면 [waitForUpOrCancellation] 이 null 이 돼 탭으로 안 친다. 길게
 * 눌렀다 떼기(창 이동하려다 안 움직인 경우)도 롱프레스 시간을 넘기면 탭으로 안 친다.
 */
@Composable
fun PlunderRow(state: PlunderDispatchTracker.State, mode: PlunderDisplayMode, onClearReady: () -> Unit = {}) {
    if (!state.anyDispatched) return
    val now = rememberCoarseNow(state)
    val anyReady = state.readyCount(now) > 0
    Row(
        modifier = Modifier.pointerInput(anyReady, onClearReady) {
            if (!anyReady) return@pointerInput
            val longPressMs = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val up = withTimeoutOrNull(longPressMs) { waitForUpOrCancellation() }
                if (up != null) onClearReady()
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("군주", color = Color(0xFF94A3B8), fontSize = 11.sp)
        when (mode) {
            PlunderDisplayMode.SEPARATE -> state.endsAtMs.filterNotNull().forEach { PlunderSlotIcon(it, now) }
            PlunderDisplayMode.COMBINED -> {
                val soonest = state.endsAtMs.filterNotNull().filter { it > now }.minOrNull()
                PlunderSlotIcon(if (anyReady) now else soonest, now)
            }
        }
    }
}

/**
 * 배지(지름 56dp)용 — 6칸 링은 너무 작아 안 보여서 칸마다 점 하나로 "보내도 되는지"만 보여준다.
 * 노랑 = 쿨다운 중, 초록 = 준비, 안 보낸 칸은 점 없음. [PlunderDisplayMode.COMBINED] 면 점 1개
 * (준비된 칸이 하나라도 있으면 초록). 이번 세션에 파견이 없으면 아무것도 안 그린다.
 */
@Composable
fun PlunderDots(state: PlunderDispatchTracker.State, mode: PlunderDisplayMode) {
    if (!state.anyDispatched) return
    val now = rememberCoarseNow(state)
    fun colorOf(endsAtMs: Long): Color = if (endsAtMs <= now) READY else RUNNING
    val colors = when (mode) {
        PlunderDisplayMode.SEPARATE -> state.endsAtMs.filterNotNull().map(::colorOf)
        PlunderDisplayMode.COMBINED -> listOf(if (state.readyCount(now) > 0) READY else RUNNING)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (c in colors) {
            Canvas(Modifier.size(5.dp)) { drawCircle(c) }
        }
    }
}
