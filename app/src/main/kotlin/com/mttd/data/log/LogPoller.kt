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

    /** 한 줄과 그 줄이 파일에서 시작하는 바이트 오프셋. [readRange] 로 특정 지점부터 다시 읽어야
     * 하는 소비자([com.mttd.domain.CharacterLoadoutTracker] 등)를 위한 것 — 오프셋이 필요 없으면
     * [text] 만 쓰면 된다. */
    data class Line(val text: String, val offset: Long)

    private val _lines = MutableSharedFlow<Line>(
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
    val lines: SharedFlow<Line> = _lines.asSharedFlow()

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
        } else if (initialSize > offset && initialSize - offset > STALE_GAP_BYTES) {
            // 저장된 offset 이 지금 파일 크기보다 한참(기본 10MB+) 뒤처져 있다 — 며칠 방치 후
            // 재접속했거나, 로테이션 없이 오래 켜둔 게임이라 로그가 이례적으로 커진 경우(실기기
            // 태블릿에서 1.1GB 로그 + 수백MB 밀린 offset 으로 재현). 그대로 두면 (a) 수백MB를
            // 1회 폴링당 256KB씩 읽어 캐치업하느라 실제로는 과거인 이벤트가 지금 막 일어난
            // 것처럼 수십 분간 리플레이되고, (b) 회차 시작/종료 시각이 로그 자체 타임스탬프가
            // 아니라 처리 순간의 실제 시계(System.currentTimeMillis())로 찍혀서, 리플레이 중엔
            // 수십 개 회차가 거의 동시에 열렸다 닫혀 경과시간이 전부 0에 가깝게 뭉개진다.
            // 그 갭은 "새 세션" 과 똑같이 취급해 끝부터 다시 시작한다 — 밀린 구간은 이미
            // 지나간 과거라 실시간 HUD 입장에선 재생할 가치가 없다.
            Log.i(
                TAG,
                "stored offset $offset is ${(initialSize - offset) / 1_000_000}MB behind " +
                    "current size $initialSize — treating as stale, jumping to EOF",
            )
            offset = initialSize
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
                    var textStartOffset = offset

                    // 파일 최초 바이트에 있는 UTF-8 BOM(3바이트)은 첫 라인에 U+FEFF 로 보이므로
                    // 제거하고, 그만큼 이 텍스트의 파일상 시작 오프셋도 밀어준다.
                    if (offset == 0L && text.startsWith('﻿')) {
                        text = text.substring(1)
                        textStartOffset += 3
                    }

                    // 이전 pendingTail 과 결합 후 라인 분리. pendingTail 은 이전 반복에서 이미
                    // offset 에 반영된 바이트이므로, 그 시작 위치는 (이번 청크 시작) - (pendingTail
                    // 바이트 길이) 로 역산한다 — readRange() 로 특정 줄부터 다시 읽으려면 줄마다
                    // 정확한 파일 오프셋이 필요해서(CharacterLoadoutTracker 참조), split 을
                    // 델리미터 위치까지 추적하는 버전으로 바꿨다.
                    val pendingTailBytes = if (pendingTail.isEmpty()) 0
                                            else pendingTail.toString().toByteArray(Charsets.UTF_8).size
                    val combinedStartOffset = textStartOffset - pendingTailBytes
                    val combined = if (pendingTail.isEmpty()) text
                                   else pendingTail.toString() + text

                    val split = splitLines(combined, combinedStartOffset)

                    var lineCount = 0
                    for (line in split.lines) {
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
                    if (split.tailText.isNotEmpty()) {
                        val tailBytes = split.tailText.toByteArray(Charsets.UTF_8).size
                        if (tailBytes > MAX_TAIL_BYTES) {
                            _lines.emit(Line(split.tailText, split.tailStartOffset))
                            lineCount++
                        } else {
                            pendingTail.append(split.tailText)
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

    /** 완결된 라인 목록(오프셋 포함) + 미완성 tail 텍스트 + tail 이 시작하는 파일 오프셋. */
    private data class SplitResult(val lines: List<Line>, val tailText: String, val tailStartOffset: Long)

    /**
     * `combined`(파일에서 [startOffset] 부터 시작하는 텍스트)를 줄 단위로 쪼개면서 각 줄의 정확한
     * 파일 바이트 오프셋을 함께 계산한다. `\r\n`/`\r`/`\n` 델리미터는 전부 ASCII 1~2바이트라
     * 문자 길이 = 바이트 길이라서 그대로 더할 수 있다. 빈 줄도 오프셋 누적에는 반영해야 다음 줄의
     * 오프셋이 안 어긋난다.
     */
    private fun splitLines(combined: String, startOffset: Long): SplitResult {
        val result = ArrayList<Line>()
        var charCursor = 0
        var byteCursor = startOffset
        for (m in LINE_DELIM_REGEX.findAll(combined)) {
            val lineText = combined.substring(charCursor, m.range.first)
            if (lineText.isNotEmpty()) result += Line(lineText, byteCursor)
            val lineBytes = if (lineText.isEmpty()) 0 else lineText.toByteArray(Charsets.UTF_8).size
            byteCursor += lineBytes + m.value.length
            charCursor = m.range.last + 1
        }
        return SplitResult(result, combined.substring(charCursor), byteCursor)
    }

    /**
     * [fromOffset] 부터 파일 현재 끝까지를 폴링 루프와 무관하게 독립적으로 다시 읽어온다.
     *
     * 캐릭터 내보내기([com.mttd.domain.CharacterLoadoutTracker.lastSnapshotStartOffset]) 처럼
     * "그 시점 이후 지금까지 전부"가 필요한 1회성 요청용 — 폴링 루프가 라인 단위로 흘려보내는
     * 것과 달리, 여기선 [service]/[logPath] 를 그대로 재사용해 [readFileChunk] 를 chunk 단위로
     * 반복 호출한다. 폴러의 `offset`/`pendingTail` 상태는 건드리지 않으므로 폴링 중에도 안전하게
     * 호출할 수 있다.
     *
     * 전부-아니면-전무(all-or-nothing) 이다 — 캐릭터 내보내기는 "일부라도 빠지느니 실패가 낫다"는
     * 원칙(선별해서 보내면 정확히 뭐가 빠졌는지 알 수 없다)이라, 범위가 [maxTotalBytes] 를 넘거나
     * 중간에 청크 하나라도 못 읽으면 지금까지 읽은 걸 잘라서 반환하지 않고 통째로 실패(null) 처리한다.
     * 호출부는 null 을 "재시도해라"로 안내하면 된다(재접속하면 [fromOffset] 자체가 최신 로그인으로
     * 갱신돼 범위도 다시 작아진다).
     *
     * @param maxTotalBytes 진짜 병적인 케이스(앱을 켜놓은 채 며칠씩 재접속 없이 방치 등)에 대한
     *   순수 OOM 방지용 상한이지, 정상적인 몇 시간짜리 파밍 세션에서 걸릴 일은 없어야 한다 — 낮게
     *   잡으면 범위가 그 이하로도 흔히 넘어가 "정상 세션인데 매번 실패"가 나므로 넉넉하게 잡는다.
     *   서버 쪽 실제 제한(스펙: 압축 후 1.9MB)은 이 함수가 알 바 아니고, 넘으면 업로드가 HTTP
     *   에러로 실패하는 게 맞다 — 여기서 먼저 잘라 보내면 그게 바로 "선별 전송"이 된다.
     * @return 읽은 바이트(항상 `size - fromOffset` 전체). `fromOffset >= 현재 파일 크기`이거나
     *   서비스에 접근할 수 없거나 범위가 너무 크거나 도중에 읽기가 실패하면 null.
     */
    suspend fun readRange(fromOffset: Long, maxTotalBytes: Int = DEFAULT_MAX_RANGE_BYTES): ByteArray? =
        withContext(Dispatchers.IO) {
            val svc = service() ?: return@withContext null
            val size = try { svc.getFileSize(logPath) } catch (t: Throwable) {
                Log.w(TAG, "readRange: getFileSize failed", t); -1L
            }
            if (size < 0 || fromOffset >= size) return@withContext null
            val expected = size - fromOffset
            if (expected > maxTotalBytes) {
                Log.w(TAG, "readRange: range too large ($expected bytes > $maxTotalBytes) — refusing to send a partial slice")
                return@withContext null
            }

            val out = java.io.ByteArrayOutputStream(expected.toInt())
            var cursor = fromOffset
            while (cursor < size) {
                val want = (size - cursor).coerceAtMost(MAX_CHUNK_BYTES.toLong()).toInt()
                val chunk = try { svc.readFileChunk(logPath, cursor, want) } catch (t: Throwable) {
                    Log.w(TAG, "readRange: readFileChunk failed @$cursor", t); null
                }
                if (chunk == null || chunk.isEmpty()) {
                    Log.w(TAG, "readRange: short read @$cursor (wanted $want, expected up to $size) — refusing partial slice")
                    return@withContext null
                }
                out.write(chunk)
                cursor += chunk.size
            }
            out.toByteArray()
        }

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
        /**
         * 저장된 offset 이 이 이상 뒤처져 있으면 "새 세션" 취급해 끝부터 다시 시작한다.
         * 활발한 파밍 기준 초당 ~100줄 × ~80바이트 ≈ 8KB/s 이므로, 10MB 갭은 대략 20분
         * 분량 — 그 정도까지는 "잠깐 자리 비움" 으로 보고 정상 캐치업, 그보다 크면 리플레이
         * 자체가 더 해롭다고 판단한다(위 stale-gap 분기 주석 참조).
         */
        const val STALE_GAP_BYTES = 10_000_000L
        const val MAX_CHUNK_BYTES = 262_144  // 256 KB
        const val MAX_TAIL_BYTES = 65_536    // 이보다 큰 미완성 라인은 강제 flush (OOM 방지)
        /**
         * [readRange] 기본 상한. all-or-nothing 이라 이 값을 넘기면 통째로 실패하므로, 정상
         * 파밍 세션(초당 최대 ~100줄, 몇 시간)에서는 절대 안 걸릴 만큼 넉넉하게 잡는다 —
         * 재접속 없이 방치된 극단적 케이스에 대한 OOM 방지용 최후 방어선일 뿐이다.
         */
        const val DEFAULT_MAX_RANGE_BYTES = 64 * 1024 * 1024
        private val LINE_DELIM_REGEX = Regex("\r\n|\r|\n")
    }
}
