package com.mttd.data.adb.starter

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.mttd.service.UserService
import java.io.File

/**
 * `app_process` 로 shell UID 백그라운드 프로세스로 띄우는 진입점(Shizuku `ServiceStarter` 와
 * 같은 역할, [HiddenApis] 클래스 doc 참조 — 새로 작성, Shizuku 소스 포팅 아님).
 *
 * [com.mttd.service.UserService] 는 `shizuku` 플레이버가 실제 Shizuku 프로세스 안에서 쓰는
 * 그 구현체를 그대로 재사용한다 — `app_process` 에 우리 앱 APK 를 CLASSPATH 로 넘기면 같은 dex
 * 안의 이 클래스를 그대로 로드할 수 있어서, 파일 접근 로직([com.mttd.data.GameFileAccessPolicy]
 * 화이트리스트 포함)을 중복 구현할 필요가 없다.
 *
 * 뜬 뒤엔 [DirectUserServiceProvider] (앱 프로세스, Zygote로 정상 기동된 쪽)에 Binder를
 * ContentProvider `call()` 로 넘긴다 — 실제 서비스는 이 Binder를 통해 앱이 직접 붙어서 쓴다.
 * Binder 의 실체(UserService 인스턴스)가 이 프로세스에 있으므로, 이 프로세스가 죽으면 앱이
 * 들고 있는 참조도 함께 죽는다 — 그래서 한 번 보내고 끝내지 않고 두 가지를 계속 반복한다:
 *
 * 1. **주기적 재전송**: 앱 프로세스가 (크래시나 OS의 일시적 백그라운드 kill 등으로) 재시작돼도
 *    Binder 참조를 잃어버린다. 앱은 재시작 시 스스로 "이미 뜬 데몬이 있으니 그걸 쓰자"고 판단할
 *    방법이 없어서(먼저 연락해오는 쪽은 항상 이 데몬이다), 이 프로세스가 살아있는 한 계속
 *    재전송을 시도해야 앱이 WiFi 없이도(adb 재부트스트랩 없이) 다시 붙을 수 있다.
 * 2. **자동 만료**: 반대로 사용자가 mTTD를 완전히 안 쓰는데 이 shell UID 프로세스가 무한정
 *    남아있는 것도 바람직하지 않다. 다만 "앱이 종료됐다"는 이벤트를 이 프로세스가 직접 감지해
 *    즉시 죽는 방식은 위험하다 — [com.mttd.service.TrackerForegroundService] 는 `START_STICKY`라
 *    OS가 메모리 확보차 잠깐 죽였다 자동 재시작하는 경우에도 같은 "죽음" 신호가 뜨고, 그때
 *    데몬까지 같이 죽이면 재시작 직후 또 WiFi가 필요한 재부트스트랩이 필요해져서 이번에 고친
 *    문제가 그대로 재발한다. 그래서 즉각 반응하는 이벤트 훅 대신, [SELF_EXPIRE_AFTER_MS] 동안
 *    앱 쪽 ContentProvider에 단 한 번도 못 닿으면(=일시적 재시작이 아니라 정말 안 쓰는 것으로
 *    판단) 그때 스스로 종료한다 — 오탐(정상 사용 중인데 잘못 죽는 것) 위험을 낮추는 대신 정리가
 *    느린 쪽을 택했다.
 *
 *    참고로 이 조건은 사실상 거의 발동하지 않는다: ContentProvider 접근은 앱 프로세스가
 *    죽어있어도 안드로이드가 그 자리에서 되살려버리고(실기기 확인됨), 되살아난 프로세스는
 *    ContentProvider 를 서빙 중이라는 이유만으로 oom_score_adj=0(포그라운드급)을 받는다
 *    (실기기 확인됨) — "데몬이 방금 되살림"과 "유저가 실제로 씀"이 프로세스 존재 여부/우선순위
 *    레벨에서 구분이 안 돼서, 이 신호만으로는 진짜 유휴 상태를 감지할 수 없다. 제대로 하려면
 *    앱 쪽(예: [com.mttd.service.TrackerForegroundService] 가 마지막으로 실제 폴링한 시각)이
 *    ContentProvider 응답에 "진짜 마지막 활동 시각"을 실어 보내고 데몬이 그 값을 신뢰하는
 *    구조가 필요하다 — 이 프로세스 코드만으로는 못 고치는 범위라 일단 보류.
 */
object DirectDaemonStarter {

    private const val TAG = "mTTD.DirectDaemon"
    private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    private const val EXTRA_BINDER = "binder"

    /**
     * 이 프로세스는 `app_process`(shell UID)로 떠서 ActivityManager가 관리하는 일반 앱과 달리
     * 안드로이드의 OOM 보호 등급을 전혀 못 받는다 — 클래스 doc에서 "앱 프로세스는 ContentProvider
     * 서빙 중이라 oom_score_adj=0을 받는다"고 확인한 것과 대비되게, 이 데몬 자신은 그런 보호가
     * 전혀 없다. 메모리 압박 시 LMKD가 이유 없이 이 프로세스부터 죽였을 가능성을 낮추려고
     * 자기 자신의 oom_score_adj를 낮춰본다.
     *
     * shell 도메인 SELinux 정책이 이 쓰기를 막을 수도 있어 성공을 보장 못 한다 — 실패해도
     * 데몬 동작에는 영향 없으므로 그냥 로그만 남기고 넘어간다(다음 logcat/진단 로그로 성공
     * 여부 확인 가능).
     */
    private fun tryProtectFromOomKiller() {
        try {
            File("/proc/self/oom_score_adj").writeText("-800")
            Log.i(TAG, "oom_score_adj set to -800")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to set oom_score_adj (SELinux denial or unsupported) — daemon still runs unprotected", t)
        }
    }

    /** 이 주기로 계속 재전송 시도 — 성공/실패와 무관하게 그냥 반복(무거운 작업 아님). */
    private const val ANNOUNCE_INTERVAL_MS = 15_000L

    /** 클래스 doc의 "자동 만료" 참조 — 넉넉하게 잡아 정상적인 재시작/백그라운드 전환 중 오탐 방지. */
    private const val SELF_EXPIRE_AFTER_MS = 12 * 60 * 60_000L // 12시간

    // DirectAdbManager.captureLogcatText() 의 폴백이 같은 파일명을 참조한다.
    const val LOGCAT_SNAPSHOT_FILE = "mttd_daemon_logcat.txt"
    private const val LOGCAT_SNAPSHOT_LINES = 1000

    // DirectAdbManager.handleDaemonBinder() 의 linkToDeath 콜백이 죽는 순간 즉시 남기는 캡처 —
    // 이 데몬이 살아있는 동안 주기적으로 남기는 [LOGCAT_SNAPSHOT_FILE] 보다 훨씬 죽는 시점에
    // 가깝다(최대 수십 초 vs 수 밀리초 오차). captureLogcatText() 폴백에서 이걸 우선한다.
    const val DEATH_LOGCAT_FILE = "mttd_daemon_death_logcat.txt"

    // 전체 logcat 은 실기기에서 한 번에 수만 자(AOD/센서 등 무관한 시스템 로그가 대부분)라, 진단
    // 로그를 공유받는 사람이 그 안에서 죽는 순간 줄을 직접 찾아 다시 보내달라고 부탁하는 게
    // 비현실적이었다(실제로 요청해봤지만 카카오톡 공유 파일에서 재검색해 오려달라는 건
    // 테스터 입장에서 부담). 그래서 데몬이 쓸 때부터 관련 있어 보이는 줄만 남긴다.
    // DirectAdbManager 의 죽음-직후 즉시 캡처(handleDaemonBinder 의 linkToDeath)도 같은 필터를
    // 재사용한다 — public.
    val LOGCAT_KEYWORDS = Regex(
        "mttd|app_process|kill|died|death|lmkd|lowmemorykiller|\\boom\\b|frozen|freez|cached",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 죽는 게 진짜 메모리 부족(LMKD) 때문인지 판단할 근거 — 스냅샷마다 그 순간의 여유 메모리와
     * 이 프로세스의 실제 kill 우선순위를 같이 남긴다. [tryProtectFromOomKiller] 로 설정을
     * 시도한 oom_score_adj 와, 커널이 그걸 바탕으로 실시간 계산하는 oom_score(낮을수록 안전)는
     * 다른 값이라 — 설정이 실제로 반영됐는지도 이걸로 확인된다.
     *
     * cgroup도 같이 남긴다 — 실기기 리포트에서 데몬 죽음과 raw adb 연결 끊김이 같은 밀리초에
     * 겹친 사례가 나왔는데, `fork()`는 부모의 cgroup 소속을 그대로 물려받고 `setsid()`로는 그게
     * 안 바뀐다. 그래서 "무선 디버깅이 자기 adb 세션에 딸린 cgroup을 정리하면서, 이중 fork로
     * 세션/프로세스그룹만 분리했을 뿐인 이 데몬까지 같이 걷힌다"는 가설을 세웠다 — 자기 cgroup을
     * 직접 옮기는 방어는 실기기에서 확인해보니 shell UID로는 모든 계층(v2 unified, cpuset,
     * cpuctl)이 Permission denied라 불가능해서(권한 자체가 없음), 대신 다음 재현에서 죽기 직전
     * cgroup 경로가 실제로 무엇이었는지를 증거로 남겨서 가설을 확정/기각한다.
     */
    private fun memInfoLine(): String {
        val meminfo = try {
            File("/proc/meminfo").readLines()
                .filter { it.startsWith("MemTotal") || it.startsWith("MemAvailable") || it.startsWith("MemFree") }
                .joinToString(", ") { it.trim() }
        } catch (t: Throwable) {
            "meminfo 읽기 실패: ${t.javaClass.simpleName}"
        }
        val oomScoreAdj = try { File("/proc/self/oom_score_adj").readText().trim() } catch (t: Throwable) { "?" }
        val oomScore = try { File("/proc/self/oom_score").readText().trim() } catch (t: Throwable) { "?" }
        val cgroup = try {
            File("/proc/self/cgroup").readLines().joinToString(";") { it.trim() }
        } catch (t: Throwable) {
            "cgroup 읽기 실패: ${t.javaClass.simpleName}"
        }
        return "$meminfo | oom_score_adj=$oomScoreAdj oom_score=$oomScore | cgroup=$cgroup"
    }

    /**
     * [DirectAdbManager.captureLogcatText] 는 앱의 adb TCP 연결(loopback)을 통해 살아있는 동안만
     * logcat을 뜰 수 있는데, 정작 필요한 순간(이 데몬이 알 수 없는 이유로 죽는 순간)엔 그 연결도
     * 같이 끊겨있는 경우가 실기기에서 반복 확인됐다 — SIGKILL로 죽으면 죽기 직전 코드를 실행할
     * 기회 자체가 없으므로, "죽고 나서 남기기"가 아니라 **살아있는 동안 주기적으로 최신 상태를
     * 파일에 스냅샷**해두는 방식으로 바꾼다. 이 프로세스는 shell UID라 adb 연결과 무관하게
     * `/sdcard/Android/data/<pkg>/files/`(앱의 external files dir와 동일 경로)에 직접 쓸 수 있고,
     * 앱은 그 경로를 자기 것이라 별도 권한 없이 바로 읽을 수 있다. 임시 파일에 쓰고 rename하는
     * 이유: 쓰는 도중 죽으면(드물지만) 앱이 반쯤 쓰인 파일을 읽는 걸 피하려는 것.
     */
    private fun dumpLogcatSnapshot(filesDir: String) {
        try {
            val dir = File(filesDir)
            // 앱을 막 (재)설치한 직후엔 앱이 getExternalFilesDir() 를 아직 한 번도 안 불러서
            // 이 디렉터리 자체가 없을 수 있다 — 조용히 포기하지 않고 만들어본다(shell UID도
            // 보통 /sdcard 밑은 쓰기 가능). 실기기에서 이것 때문에 스냅샷이 통째로 안 만들어져
            // "logcat 캡처 실패" 폴백조차 못 쓴 채로 죽은 사례가 있었다.
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "logcat snapshot dir missing and mkdirs failed: $dir")
                return
            }
            val tmp = File(dir, "$LOGCAT_SNAPSHOT_FILE.tmp")
            val out = File(dir, LOGCAT_SNAPSHOT_FILE)
            val metaTmp = File(dir, "$LOGCAT_SNAPSHOT_FILE.meta.tmp")
            val meta = File(dir, "$LOGCAT_SNAPSHOT_FILE.meta")
            val proc = ProcessBuilder("/system/bin/logcat", "-d", "-t", LOGCAT_SNAPSHOT_LINES.toString())
                .redirectErrorStream(true)
                .start()
            var total = 0
            var kept = 0
            tmp.bufferedWriter().use { writer ->
                proc.inputStream.bufferedReader().forEachLine { line ->
                    total++
                    if (LOGCAT_KEYWORDS.containsMatchIn(line)) {
                        kept++
                        writer.write(line)
                        writer.newLine()
                    }
                }
            }
            proc.waitFor()
            metaTmp.writeText(
                "${memInfoLine()}\n" +
                    "${total}줄 중 관련 키워드(${LOGCAT_KEYWORDS.pattern}) 매칭 ${kept}줄만 남김\n",
            )
            // 로그 내용을 먼저 반영하고 meta를 나중에 반영한다 — 둘 다 tmp+rename이라 각각은
            // 원자적이지만, 이 사이에 죽으면(드물게) "최신 meta + 이전 로그" 조합보다는
            // "최신 로그 + 이전(또는 없는) meta" 쪽이 헷갈릴 여지가 적다.
            tmp.renameTo(out)
            metaTmp.renameTo(meta)
        } catch (t: Throwable) {
            Log.w(TAG, "logcat snapshot failed", t)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val params = args.associate { arg ->
            val idx = arg.indexOf('=')
            if (idx < 0) arg to "" else arg.substring(0, idx) to arg.substring(idx + 1)
        }
        val authority = params["--authority"] ?: error("--authority= required")
        val callingPkg = params["--pkg"] ?: error("--pkg= required")
        // 앱이 실제로 쓰는 getExternalFilesDir() 절대경로를 그대로 넘겨받는다 — /sdcard/Android/
        // data/<pkg>/files 로 직접 재구성하면 멀티유저/듀얼앱(국내 삼성 기기에 흔함) 환경에서
        // 앱 쪽이 보는 경로(예: /storage/emulated/10/...)와 이 shell 프로세스가 보는 /sdcard
        // 심볼릭 링크(보통 소유자/기본 유저 기준)가 서로 달라질 수 있어, 값을 직접 넘겨받는 게
        // 훨씬 안전하다.
        val filesDir = params["--filesdir"]

        tryProtectFromOomKiller()

        val service = UserService()
        val extras = Bundle()
        extras.putBinder(EXTRA_BINDER, service.asBinder())

        // 시작 시점을 첫 기준점으로 삼는다 — 앱이 아직 뜨기 전이라 첫 시도가 바로 실패해도
        // SELF_EXPIRE_AFTER_MS 전체를 유예로 준다(막 시작했는데 바로 만료 판정하면 안 됨).
        // 벽시계(currentTimeMillis)가 아니라 elapsedRealtime을 쓴다 — NITZ 재동기화나 사용자의
        // 수동 시각 변경으로 벽시계가 갑자기 크게 튀면, 실제로는 정상 사용 중인데도 이 판정이
        // "장시간 실패"로 오인해 데몬이 즉시 자살할 수 있다(WiFi 없는 LTE 상황이면 앱이 자동
        // 재부트스트랩도 못 해서 사용자가 WiFi를 다시 켜기 전까진 복구 불가).
        var lastSuccessAtMs = SystemClock.elapsedRealtime()

        // logcat 스냅샷은 매번 새 서브프로세스를 띄운다 — 재전송 주기(15초)마다 매번 찍으면
        // 안드로이드 12+ phantom process killer(프로세스가 자식을 과도하게 스폰하면 잡는 기능)를
        // 자극할 가능성이 있다. 실제로 이 스냅샷 기능을 넣은 뒤 죽는 시간이 46초→30초→19초로
        // 점점 빨라지는 패턴이 관측됐다(우연일 수도 있으나 무시하기엔 일관적) — 진단 도구가
        // 관측 대상 자체를 더 빨리 죽이는 역효과 위험을 줄이려고 절반 주기로만 찍는다. 그리고
        // 루프 진입 전이 아니라 진입 뒤(첫 announce 시도 다음)에 처음 찍는다 — 앱과의 첫 Binder
        // 핸드오프가 이 블로킹 서브프로세스 호출 때문에 지연되면(특히 로우엔드 기기) 이 데몬
        // 구조가 원래 해결하려던 "빠른 재연결"이라는 목적과 정면으로 어긋난다.
        var loopCount = 0

        while (true) {
            try {
                HiddenApis.callProvider(authority, callingPkg, METHOD_SEND_USER_SERVICE, extras)
                lastSuccessAtMs = SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                // 앱이 지금 안 떠 있거나(정상적인 경우) 일시적 오류일 뿐 — 조용히 넘어가고
                // 다음 주기에 다시 시도한다. 얼마나 오래 실패했는지만 아래에서 판단.
                if (SystemClock.elapsedRealtime() - lastSuccessAtMs > SELF_EXPIRE_AFTER_MS) {
                    System.exit(0)
                }
            }
            loopCount++
            if (filesDir != null && loopCount % 2 == 1) dumpLogcatSnapshot(filesDir)
            Thread.sleep(ANNOUNCE_INTERVAL_MS)
        }
    }
}
