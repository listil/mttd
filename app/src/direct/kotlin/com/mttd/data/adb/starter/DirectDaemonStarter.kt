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
 * ContentProvider `call()` 로 넘기고 바로 종료 — 실제 서비스는 이 Binder를 통해 앱이 직접
 * 붙어서 쓰고, 이 starter 프로세스 자신은 더 할 일이 없다(Shizuku 의 `Looper.loop()` 로 계속
 * 사는 방식과 달리, UserService 는 상태가 없는 Stub이라 프로세스를 살려둘 이유가 없다 — 대신
 * shell UID 프로세스 자체가 아니라 **Binder를 받은 앱 프로세스 쪽**이 그 Binder를 계속 참조하는
 * 한 유효하다. 단, Binder 뒤의 실체가 이 프로세스에 있으므로 이 프로세스가 죽으면 Binder도
 * 죽는다 — 그래서 실제로는 살아있어야 한다. [main] 끝에서 리턴하지 않고 대기).
 */
object DirectDaemonStarter {

    private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    private const val EXTRA_BINDER = "binder"

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

        HiddenApis.callProvider(authority, callingPkg, METHOD_SEND_USER_SERVICE, extras)

        // Binder 의 실체(UserService 인스턴스)가 이 프로세스에 있으므로, 이 프로세스가 죽으면
        // 앱 쪽이 들고 있는 IUserService 참조도 함께 죽는다 — 그래서 절대 리턴하지 않고 대기한다.
        // 앱 쪽에서 `binder.linkToDeath()` 로 이 죽음을 감지해 재부트스트랩을 트리거한다.
        Thread.sleep(Long.MAX_VALUE)
    }
}
