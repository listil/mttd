package com.mttd.data.log

import android.util.Log
import com.mttd.IUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 로그 파일 폴링 워커.
 *
 * ETor 데스크톱 앱과 동일한 전략:
 * - `getFileSize` 로 크기 폴링
 * - 크기가 커지면 `readFileChunk(offset, min(delta, MAX_CHUNK))` 로 증분 read
 * - 라인 분리 후 [lines] SharedFlow 로 방출
 * - offset 은 [OffsetStore] 에 3초 debounce 로 persist
 * - 폴링 인터벌 동적 조정: 활성 시 [MIN_INTERVAL_MS], idle 시 [MAX_INTERVAL_MS]
 *
 * 서비스가 관장하는 라이프사이클이므로 [start]/[stop] 만 노출.
 */
class LogPoller(
    private val service: () -> IUserService?,
    private val offsetStore: OffsetStore,
    private val logPath: String,
) {

    private val _lines = MutableSharedFlow<String>(
        replay = 0,
        // `GetPlayerData` 같은 대형 Socket 응답(CharacterLoadoutTracker 참조)은 한 청크(256KB)에
        // 수천 개의 짧은 `+key [value]` 줄이 실려 오는데, DROP_OLDEST + tryEmit 조합으로는
        // 소비 측이 못 따라갈 때 줄이 조용히 버려진다 — 처음엔 블록 시작 마커까지 씹혀서 캐릭터
        // 스냅샷이 아예 안 잡혔고, 버퍼를 1024→16384 로 키운 뒤엔 시작은 잡히는데 블록 앞쪽
        // (skillLayout 등) 라인이 드문드문 드롭돼 재로그인마다 스킬 목록이 들쭉날쭉해지는
        // 형태로 재현됐다 — 둘 다 실기기에서 확인. 버퍼 크기로는 근본 해결이 안 되므로
        // SUSPEND 로 바꿔서 emit() 이 진짜 백프레셔를 걸게 한다 (드롭 자체가 안 남).
        extraBufferCapacity = 16384,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val _status = MutableStateFlow(PollingStatus())
    val status: StateFlow<PollingStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        job = s.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        _status.value = _status.value.copy(active = false)
    }

    private suspend fun pollLoop() {
        _status.value = _status.value.copy(active = true, logPath = logPath)

        var offset = offsetStore.load(logPath)
        var intervalMs = MIN_INTERVAL_MS
        var idleCount = 0
        val timeSource = TimeSource.Monotonic
        var lastPersistMark = timeSource.markNow()
        var lastPersistedOffset = offset

        // 청크 경계에서 라인이 잘리는 문제를 방지하기 위한 tail 버퍼.
        // 청크가 개행으로 끝나지 않으면 마지막 부분을 다음 청크에 이어붙임.
        var pendingTail = StringBuilder()

        // 최초 진입: 지금 파일 끝부터 시작 (기존 로그 전부 방출 방지).
        // 저장된 offset 이 있고 파일이 그만큼 커져 있으면 그 지점부터 읽음.
        val initialSize = svcSize()
        if (initialSize >= 0 && offset > initialSize) {
            // 파일 truncate 감지 — 시작점 리셋
            offset = 0
        }
        if (offset == 0L && initialSize > 0) {
            offset = initialSize   // "새 세션": 끝부터
            offsetStore.save(logPath, offset)
            lastPersistedOffset = offset
        }
        _status.value = _status.value.copy(offset = offset)

        while (coroutineScopeIsActive()) {
            val size = svcSize()

            if (size < 0) {
                // 파일 사라짐 (rotation? uninstall?) — 인터벌 늘리고 재시도
                intervalMs = MAX_INTERVAL_MS
                _status.value = _status.value.copy(
                    intervalMs = intervalMs,
                    fileSize = -1,
                    lastError = "file not accessible",
                )
                delay(intervalMs)
                continue
            }

            if (size < offset) {
                // Truncate/rotate 감지
                Log.i(TAG, "file truncated: was $offset, now $size — resetting offset")
                offset = 0
                offsetStore.save(logPath, offset)
                lastPersistedOffset = offset
                pendingTail.clear()
            }

            if (size > offset) {
                idleCount = 0
                intervalMs = (intervalMs / 2).coerceAtLeast(MIN_INTERVAL_MS)

                val remaining = size - offset
                val chunkSize = remaining.coerceAtMost(MAX_CHUNK_BYTES.toLong()).toInt()
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        service()?.readFileChunk(logPath, offset, chunkSize)
                    } catch (t: Throwable) {
                        Log.w(TAG, "readFileChunk failed", t)
                        null
                    }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    var text = bytes.toString(Charsets.UTF_8)

                    // 파일 최초 바이트에 있는 UTF-8 BOM 은 첫 라인에 U+FEFF 로 보이므로 제거.
                    if (offset == 0L && text.startsWith('﻿')) {
                        text = text.substring(1)
                    }

                    // 이전 pendingTail 과 결합 후 라인 분리.
                    val combined = if (pendingTail.isEmpty()) text
                                   else pendingTail.toString() + text
                    val endsWithNewline = combined.endsWith('\n') || combined.endsWith('\r')

                    // '\r\n', '\r', '\n' 모두 처리.
                    val parts = combined.split(Regex("\\r\\n|\\r|\\n"))
                    val completeCount = if (endsWithNewline) parts.size else parts.size - 1

                    var lineCount = 0
                    for (i in 0 until completeCount) {
                        val line = parts[i]
                        if (line.isEmpty()) continue
                        // tryEmit 은 버퍼가 꽉 차면 DROP_OLDEST 로 조용히 라인을 버린다 — 픽업/맵진입
                        // 처럼 짧은 버스트에선 안 걸렸지만, GetPlayerData 처럼 한 청크에 수천 줄이
                        // 몰리는 대형 응답(CharacterLoadoutTracker 참조)에선 소비 측이 못 따라갈 때
                        // 블록 앞쪽 줄(예: skillLayout)이 실기기에서 실제로 드롭됐다 — 버퍼를 아무리
                        // 키워도 근본 해결이 안 돼 suspend emit 으로 백프레셔를 걸어 드롭을 없앴다.
                        _lines.emit(line)
                        lineCount++
                    }

                    // 마지막 조각이 미완성이면 tail 로 보관 (엄청 큰 경우 강제 flush)
                    pendingTail.clear()
                    if (!endsWithNewline && parts.isNotEmpty()) {
                        val tail = parts.last()
                        if (tail.length > MAX_TAIL_BYTES) {
                            _lines.emit(tail)
                            lineCount++
                        } else {
                            pendingTail.append(tail)
                        }
                    }

                    offset += bytes.size
                    _status.value = _status.value.copy(
                        offset = offset,
                        fileSize = size,
                        // 게임은 대기 중에도 TCP Ping 을 초당 1 회 남긴다 (실측 89 B/s).
                        // 그래서 "로그가 자라고 있다" = "게임이 켜져 있다" 로 봐도 된다.
                        lastGrowthAtMs = System.currentTimeMillis(),
                        totalBytesRead = _status.value.totalBytesRead + bytes.size,
                        totalLinesEmitted = _status.value.totalLinesEmitted + lineCount,
                        lastError = null,
                    )
                }
            } else {
                idleCount++
                // 로그가 오래 안 커지면 게임을 안 하는 것 → 거의 잠든다.
                // 폴링 1 회 = Shizuku 바인더 IPC + stat 이므로 그대로 배터리 비용.
                intervalMs = when {
                    idleCount >= DEEP_IDLE_THRESHOLD -> DEEP_IDLE_INTERVAL_MS
                    idleCount >= IDLE_THRESHOLD -> MAX_INTERVAL_MS
                    idleCount >= 5 -> MID_INTERVAL_MS
                    else -> intervalMs
                }
                _status.value = _status.value.copy(
                    intervalMs = intervalMs,
                    fileSize = size,
                )
            }

            // Offset persist (3초 debounce)
            if (offset != lastPersistedOffset && lastPersistMark.elapsedNow() >= 3.seconds) {
                offsetStore.save(logPath, offset)
                lastPersistedOffset = offset
                lastPersistMark = timeSource.markNow()
            }

            delay(intervalMs)
        }

        // 종료 시 마지막 offset persist
        if (offset != lastPersistedOffset) offsetStore.save(logPath, offset)
    }

    private suspend fun svcSize(): Long = withContext(Dispatchers.IO) {
        try {
            service()?.getFileSize(logPath) ?: -1L
        } catch (t: Throwable) {
            Log.w(TAG, "getFileSize failed", t)
            -1L
        }
    }

    private fun coroutineScopeIsActive() = scope?.isActive == true

    data class PollingStatus(
        val active: Boolean = false,
        val logPath: String? = null,
        val offset: Long = 0,
        val fileSize: Long = 0,
        val intervalMs: Long = MIN_INTERVAL_MS,
        /** 로그가 마지막으로 자란 시각. 0 이면 아직 한 번도 관측 못 함. */
        val lastGrowthAtMs: Long = 0,
        val totalBytesRead: Long = 0,
        val totalLinesEmitted: Long = 0,
        val lastError: String? = null,
    ) {
        /**
         * 게임이 실행 중일 가능성이 높은가.
         *
         * 게임은 대기 상태에서도 로그를 계속 쓰므로(초당 Ping), 최근에 파일이 자랐다면
         * 게임이 켜져 있다는 뜻이다. 폴링 간격이 최대 30 초까지 늘어나므로 여유를 두고 판단한다.
         *
         * 폴러가 안 돌고 있으면(active=false) 판단할 수 없으므로 "실행 중"으로 간주해
         * 삭제 같은 위험한 동작을 막는다 (안전한 쪽으로).
         */
        fun gameLikelyRunning(nowMs: Long = System.currentTimeMillis()): Boolean = when {
            !active -> true
            lastGrowthAtMs == 0L -> false          // 폴링 시작 후 한 번도 안 자람 = 꺼져 있음
            else -> nowMs - lastGrowthAtMs < GAME_IDLE_GRACE_MS
        }
    }

    companion object {
        private const val TAG = "mTTD.Poller"
        /**
         * 폴링 간격. 한 번 폴링할 때마다 Shizuku 프로세스로 바인더 IPC + stat 이 나가므로
         * 그대로 배터리 비용이다.
         *
         * HUD 가 1 초 주기로 갱신되고 사용자가 체감하는 지연도 그 수준이라
         * 최소 간격을 300 ms 로 둘 이유가 없다 (초당 3.3 회 → 1 회로).
         * 게임을 안 하는 동안(로그가 안 커짐)에는 5 초까지 늘려서 거의 잠들게 한다.
         */
        const val MIN_INTERVAL_MS = 1000L
        const val MID_INTERVAL_MS = 2000L
        const val MAX_INTERVAL_MS = 5000L
        const val IDLE_THRESHOLD = 15
        /**
         * 이만큼 연속으로 로그가 안 커지면 게임을 안 하는 것으로 보고 30 초 간격까지 늘린다.
         * (5 초 × 24 ≈ 2 분 무변화) 게임을 다시 켜면 첫 증가 감지 시 바로 1 초로 복귀.
         */
        const val DEEP_IDLE_THRESHOLD = 24
        const val DEEP_IDLE_INTERVAL_MS = 30_000L
        /**
         * 이 시간 동안 로그가 안 자라면 게임이 꺼진 것으로 본다.
         * 최대 폴링 간격(30 초)보다 넉넉해야 오판하지 않는다.
         */
        const val GAME_IDLE_GRACE_MS = 45_000L
        const val MAX_CHUNK_BYTES = 262_144  // 256 KB
        const val MAX_TAIL_BYTES = 65_536    // 이보다 큰 미완성 라인은 강제 flush (OOM 방지)
    }
}
