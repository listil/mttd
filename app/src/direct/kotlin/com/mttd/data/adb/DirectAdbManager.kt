package com.mttd.data.adb

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import com.mttd.IUserService
import com.mttd.data.GameFileAccessPolicy
import com.mttd.data.access.PrivilegedAccessManager
import com.mttd.diagnostics.DiagnosticLog
import com.mttd.service.DirectAdbPairingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "mTTD.DirectAdb"
private const val CONNECT_HOST = "127.0.0.1" // 페어링/연결 전부 같은 기기 자기 자신 대상.

/**
 * Shizuku 없이 기기의 무선 디버깅(Android 11+) 에 직접 페어링/연결해서 [IUserService] 를
 * 제공하는 `direct` flavor 전용 구현체.
 *
 * 존재 이유: `Shizuku.bindUserService()` 가 일부 기기(OEM 백그라운드 제한 등)에서 예외도
 * 콜백도 없이 영원히 멈추는 알려진 업스트림 버그(RikkaApps/Shizuku#475, #451) 때문에, 그
 * 기기들에서는 Shizuku 자체를 우회하는 것 말고는 방법이 없었다 — 이 클래스가 그 우회 경로.
 *
 * 페어링 코드 입력은 [DirectAdbPairingService] 의 알림(RemoteInput)이 담당한다 — 설정 앱의
 * "페어링 코드로 기기 페어링" 화면을 유저가 벗어나면(=우리 앱으로 전환하면) 그 페어링 세션
 * 자체가 끊기기 때문에, 화면 전환 없이 알림 서랍에서 코드만 입력받아야 한다.
 *
 * [AdbClient] 하나를 계속 물고 있다가 매 [IUserService] 호출을 `shell:<cmd>` 한 번으로
 * 처리한다. 호출마다 새로 연결하면 TCP+TLS+ADB 핸드셰이크를 반복해서
 * [com.mttd.data.log.LogPoller] 의 폴링 성능에 치명적이므로 주의.
 */
class DirectAdbManager(private val appContext: Context) : PrivilegedAccessManager {

    private val prefs = appContext.getSharedPreferences("direct_adb", Context.MODE_PRIVATE)
    private val keyStore = PreferenceAdbKeyStore(prefs)
    private val key by lazy(LazyThreadSafetyMode.NONE) { AdbKey(keyStore, "mTTD") }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connected = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** [DirectAdbStatusCard][com.mttd.ui.onboarding.DirectAdbStatusCard] 진행 상태 표시용. */
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    enum class Status { IDLE, SEARCHING, WAITING_FOR_CODE, PAIRING, CONNECTING, CONNECTED, FAILED }

    @Volatile
    private var client: AdbClient? = null
    private val clientLock = Any()

    @Volatile
    override var service: IUserService? = null
        private set

    override fun start() {
        DiagnosticLog.log(appContext, "DirectAdb", "start()")
        service = LocalUserService()
    }

    fun hasSavedConnection(): Boolean = prefs.getString(KEY_PORT, null) != null

    /** [DirectAdbStatusCard] 의 "페어링 시작" 버튼 — 알림 기반 코드 입력 플로우를 띄운다. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun startPairing() {
        _lastError.value = null
        _status.value = Status.SEARCHING
        appContext.startForegroundService(DirectAdbPairingService.startIntent(appContext))
    }

    /**
     * [DirectAdbPairingService] 가 알림 RemoteInput 으로 코드를 받으면 호출 — 실제 SPAKE2
     * 페어링 후, 이어서 연결 포트를 mDNS 로 찾아 접속까지 마친다.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun completePairing(pairPort: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _status.value = Status.PAIRING
            val ok = AdbPairingClient(CONNECT_HOST, pairPort, code, key).use { it.start() }
            if (!ok) {
                _status.value = Status.FAILED
                _lastError.value = "페어링 실패 (코드를 다시 확인해주세요)"
                DiagnosticLog.log(appContext, "DirectAdb", "pairing failed")
                return@withContext Result.failure(AdbInvalidPairingCodeException())
            }
            DiagnosticLog.log(appContext, "DirectAdb", "pairing succeeded, discovering connect port")

            _status.value = Status.CONNECTING
            val connectPort = discoverPort(AdbMdns.TLS_CONNECT, DISCOVER_TIMEOUT_MS)
            if (connectPort == null) {
                _status.value = Status.FAILED
                _lastError.value = "페어링은 됐는데 연결 포트를 못 찾았어요 — 무선 디버깅이 켜져있는지 확인해주세요"
                DiagnosticLog.log(appContext, "DirectAdb", "connect port discovery timed out after pairing")
                return@withContext Result.failure(IllegalStateException("connect port not found"))
            }

            prefs.edit().putInt(KEY_PORT, connectPort).apply()
            connectLocked(connectPort)
            _status.value = Status.CONNECTED
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "completePairing failed", t)
            _status.value = Status.FAILED
            _lastError.value = "페어링 실패: ${t.message ?: t.javaClass.simpleName}"
            DiagnosticLog.log(appContext, "DirectAdb", "completePairing failed: ${t.javaClass.simpleName}: ${t.message}")
            Result.failure(t)
        }
    }

    /**
     * 재연결이 이미 진행 중인지 — [TrackerForegroundService] 의 3초 재시도 루프와
     * [MainActivity][com.mttd.MainActivity]`.onResume()` 이 둘 다 이걸 부르는데, 이전 시도가
     * 아직 안 끝났으면 또 부르지 않는다. 이게 없으면 두 호출이 겹쳐서 동시에 여러 연결을 만들고
     * 버리는 레이스가 생겼다(진단 로그에 `connected on port N` 이 수 ms 간격으로 여러 번 찍힘).
     */
    @Volatile
    private var reconnecting = false

    /** 부트스트랩 재시도 루프용 — 알림/다이얼로그 없이, 저장된 포트로만 재연결 시도. */
    override fun retryConnect() {
        if (_connected.value || reconnecting) return
        val savedPort = prefs.getInt(KEY_PORT, -1)
        if (savedPort <= 0) return
        reconnecting = true
        _status.value = Status.CONNECTING
        managerScope.launch {
            try {
                connectLocked(savedPort)
                _status.value = Status.CONNECTED
            } catch (t: Throwable) {
                // 저장된 포트가 이제 안 맞을 수 있다(무선 디버깅 재시작 등) — mDNS로 다시 찾아본다.
                @Suppress("NewApi")
                val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    discoverPort(AdbMdns.TLS_CONNECT, DISCOVER_TIMEOUT_MS)
                } else null
                if (fresh != null) {
                    try {
                        prefs.edit().putInt(KEY_PORT, fresh).apply()
                        connectLocked(fresh)
                        _status.value = Status.CONNECTED
                        return@launch
                    } catch (t2: Throwable) {
                        Log.w(TAG, "retryConnect (rediscovered port) failed", t2)
                    }
                }
                Log.w(TAG, "retryConnect failed", t)
                _status.value = Status.IDLE
                _lastError.value = "재연결 실패: ${t.message ?: t.javaClass.simpleName}"
                DiagnosticLog.log(appContext, "DirectAdb", "retryConnect failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                reconnecting = false
            }
        }
    }

    /** 이 flavor엔 "권한 요청 다이얼로그"가 없다 — 조용한 재연결만. 페어링은 [startPairing] 이 담당. */
    override fun requestOrReconnect() = retryConnect()

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun discoverPort(serviceType: String, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                lateinit var mdns: AdbMdns
                val observer = Observer<Int> { port ->
                    if (port > 0 && cont.isActive) {
                        cont.resume(port)
                    }
                }
                mdns = AdbMdns(appContext, serviceType, observer)
                cont.invokeOnCancellation { mdns.stop() }
                mdns.start()
            }
        }

    /**
     * "진단 로그 보내기" 가 [com.mttd.diagnostics.DiagnosticLog.extraSection] 을 통해 부른다 —
     * 디버그 빌드에서만 등록되므로(release APK 는 이 경로 자체를 안 탐) 별도 가드 불필요.
     * 파일 경로 whitelist([GameFileAccessPolicy]) 대상이 아닌 유일한 셸 명령이라 여기 독립
     * 함수로 뒀다 — [IUserService] 에는 절대 안 태운다 (op 은 이름·타입 고정 원칙, AIDL 참고).
     */
    suspend fun captureLogcatText(maxLines: Int = 500): String = withContext(Dispatchers.IO) {
        try {
            runShell("logcat -d -t $maxLines").toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "(logcat 캡처 실패: ${e.javaClass.simpleName}: ${e.message})"
        }
    }

    private fun connectLocked(port: Int) {
        synchronized(clientLock) {
            try {
                client?.close()
                val c = AdbClient(CONNECT_HOST, port, key)
                c.connect()
                client = c
                _connected.value = true
                // 이전 실패로 남아있던 에러 문구를 지운다 — 안 지우면 재연결에 성공해도 UI에
                // "재연결 실패: ..." 가 그대로 남아 유저를 헷갈리게 한다.
                _lastError.value = null
                DiagnosticLog.log(appContext, "DirectAdb", "connected on port $port")
            } catch (t: Throwable) {
                client = null
                _connected.value = false
                throw t
            }
        }
    }

    /** 연결이 끊긴 걸로 판단되면 상태를 내려서, 다음 재시도 루프가 재연결하게 한다. */
    private fun onConnectionLost(t: Throwable) {
        synchronized(clientLock) {
            try { client?.close() } catch (_: Throwable) {}
            client = null
        }
        _connected.value = false
        DiagnosticLog.log(appContext, "DirectAdb", "connection lost: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** `'` 를 포함한 경로가 올 일은 없지만(내부 생성 경로뿐) 방어적으로 이스케이프. */
    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun runShell(command: String): ByteArray {
        val c = client ?: error("not connected")
        val out = java.io.ByteArrayOutputStream()
        try {
            synchronized(clientLock) {
                c.shellCommand(command) { chunk -> out.write(chunk) }
            }
        } catch (t: Throwable) {
            onConnectionLost(t)
            throw t
        }
        return out.toByteArray()
    }

    private inner class LocalUserService : IUserService.Stub() {

        override fun getFileSize(path: String): Long {
            GameFileAccessPolicy.ensurePathAllowed(path)
            return try {
                val out = runShell("stat -c%s ${shQuote(path)} 2>/dev/null").toString(Charsets.UTF_8).trim()
                out.toLongOrNull() ?: -1L
            } catch (e: Exception) {
                Log.w(TAG, "getFileSize failed for $path", e)
                -1L
            }
        }

        override fun fileExists(path: String): Boolean {
            GameFileAccessPolicy.ensurePathAllowed(path)
            return try {
                val out = runShell("[ -e ${shQuote(path)} ] && echo 1 || echo 0").toString(Charsets.UTF_8).trim()
                out == "1"
            } catch (e: Exception) {
                false
            }
        }

        override fun readFileChunk(path: String, offset: Long, maxBytes: Int): ByteArray {
            GameFileAccessPolicy.ensurePathAllowed(path)
            require(offset >= 0) { "offset must be >= 0" }
            require(maxBytes > 0) { "maxBytes must be > 0" }
            val limit = maxBytes.coerceAtMost(GameFileAccessPolicy.MAX_CHUNK_BYTES)
            return try {
                // AIDL 문서의 "등가 셸 커맨드"를 그대로 원격 실행 — tail -c +offset+1 | head -c limit.
                runShell("tail -c +${offset + 1} ${shQuote(path)} 2>/dev/null | head -c $limit")
            } catch (e: Exception) {
                Log.w(TAG, "readFileChunk failed for $path @$offset+$limit", e)
                ByteArray(0)
            }
        }

        override fun listInstalledGamePackages(): String {
            val pkgs = GameFileAccessPolicy.knownGamePackages.joinToString(" ")
            val cmd = "for p in $pkgs; do [ -d \"/sdcard/Android/data/\$p/files/UE4Game\" ] && echo \"\$p\"; done"
            return try {
                runShell(cmd).toString(Charsets.UTF_8).trim()
            } catch (e: Exception) {
                Log.w(TAG, "listInstalledGamePackages failed", e)
                ""
            }
        }

        override fun destroy() {
            // Shizuku 의 shell-UID 프로세스 종료와 달리 여기선 같은 앱 프로세스라 할 일이 없다 —
            // 연결만 끊는다.
            onConnectionLost(IllegalStateException("destroy() called"))
        }
    }

    companion object {
        private const val KEY_PORT = "connect_port"
        private const val DISCOVER_TIMEOUT_MS = 15_000L
    }
}
