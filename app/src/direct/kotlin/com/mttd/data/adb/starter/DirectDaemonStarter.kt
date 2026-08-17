package com.mttd.data.adb.starter

import android.os.Bundle
import com.mttd.service.UserService

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

    private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    private const val EXTRA_BINDER = "binder"

    /** 이 주기로 계속 재전송 시도 — 성공/실패와 무관하게 그냥 반복(무거운 작업 아님). */
    private const val ANNOUNCE_INTERVAL_MS = 15_000L

    /** 클래스 doc의 "자동 만료" 참조 — 넉넉하게 잡아 정상적인 재시작/백그라운드 전환 중 오탐 방지. */
    private const val SELF_EXPIRE_AFTER_MS = 60 * 60_000L // 1시간

    @JvmStatic
    fun main(args: Array<String>) {
        val params = args.associate { arg ->
            val idx = arg.indexOf('=')
            if (idx < 0) arg to "" else arg.substring(0, idx) to arg.substring(idx + 1)
        }
        val authority = params["--authority"] ?: error("--authority= required")
        val callingPkg = params["--pkg"] ?: error("--pkg= required")

        val service = UserService()
        val extras = Bundle()
        extras.putBinder(EXTRA_BINDER, service.asBinder())

        // 시작 시점을 첫 기준점으로 삼는다 — 앱이 아직 뜨기 전이라 첫 시도가 바로 실패해도
        // SELF_EXPIRE_AFTER_MS 전체를 유예로 준다(막 시작했는데 바로 만료 판정하면 안 됨).
        var lastSuccessAtMs = System.currentTimeMillis()

        while (true) {
            try {
                HiddenApis.callProvider(authority, callingPkg, METHOD_SEND_USER_SERVICE, extras)
                lastSuccessAtMs = System.currentTimeMillis()
            } catch (t: Throwable) {
                // 앱이 지금 안 떠 있거나(정상적인 경우) 일시적 오류일 뿐 — 조용히 넘어가고
                // 다음 주기에 다시 시도한다. 얼마나 오래 실패했는지만 아래에서 판단.
                if (System.currentTimeMillis() - lastSuccessAtMs > SELF_EXPIRE_AFTER_MS) {
                    System.exit(0)
                }
            }
            Thread.sleep(ANNOUNCE_INTERVAL_MS)
        }
    }
}
