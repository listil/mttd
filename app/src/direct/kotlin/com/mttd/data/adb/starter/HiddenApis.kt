package com.mttd.data.adb.starter

import android.os.Build
import android.os.Bundle
import android.os.IBinder
import java.lang.reflect.Proxy

/**
 * `app_process` 로 shell UID에서 직접 띄운 [DirectDaemonStarter] 가, Zygote로 뜬 정상 앱
 * 프로세스(mTTD 자신)의 `ContentProvider` 에게 자기 Binder를 넘기기 위한 최소 리플렉션 헬퍼.
 *
 * `IActivityManager`/`IContentProvider`/`ContentProviderHolder`/`AttributionSource` 는 전부
 * `@hide` 프레임워크 클래스라 컴파일 타임에 타입을 못 쓴다 — 전부 리플렉션으로 접근한다.
 * (Shizuku 자신도 정확히 이 종류의 접근을 쓰지만, 그 유틸리티 클래스의 정확한 소스를 못 찾아서
 * — 이 파일은 AOSP 프레임워크의 공개된(문서화된 `@hide` 시그니처, 저작물이 아니라 플랫폼 API
 * 사실) 인터페이스를 근거로 새로 작성한 것이지 어디서 포팅한 게 아니다.)
 *
 * shell UID로 `app_process` 를 통해 직접 실행된 프로세스는 안드로이드의 hidden-API 강제
 * (그레이리스트 등)가 일반 zygote-fork 앱 프로세스에만 적용되는 정책이라 이 리플렉션 접근에서
 * 예외 대상이다 — 이게 이 방식이 실제로 동작하는 전제.
 */
object HiddenApis {

    private fun getActivityManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val amBinder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        val stubClass = Class.forName("android.app.IActivityManager\$Stub")
        return stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, amBinder)!!
    }

    /**
     * `IActivityManager.getContentProviderExternal(...)` — API 레벨별로 파라미터 수가 다르다.
     * 반환값은 `ContentProviderHolder.provider` 필드(`IContentProvider`, 역시 hidden) 그대로.
     */
    private fun getContentProviderExternal(name: String, userId: Int, token: IBinder?, tag: String?): Any? {
        val am = getActivityManager()
        val amClass = am.javaClass
        val holder = when {
            Build.VERSION.SDK_INT >= 29 -> {
                val m = amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
                )
                m.invoke(am, name, userId, token, tag)
            }
            Build.VERSION.SDK_INT >= 26 -> {
                val m = amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java, Int::class.javaPrimitiveType, IBinder::class.java,
                )
                m.invoke(am, name, userId, token)
            }
            else -> {
                val m = amClass.getMethod(
                    "getContentProviderExternal",
                    String::class.java, Int::class.javaPrimitiveType, IBinder::class.java,
                )
                m.invoke(am, name, userId, token)
            }
        } ?: return null
        val providerField = holder.javaClass.getField("provider")
        return providerField.get(holder)
    }

    /**
     * `IContentProvider.call(...)` — API 레벨별로 시그니처가 다르다(특히 31+ 는
     * `AttributionSource` 를 요구). `provider` 는 [Proxy] 로 감싸인 Binder 인터페이스라
     * 직접 캐스트 못 하고 [java.lang.reflect.Method] 로만 호출 가능.
     */
    private fun callOnProvider(
        provider: Any,
        callingPkg: String,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle,
    ): Bundle? {
        val providerClass = provider.javaClass
        return when {
            Build.VERSION.SDK_INT >= 31 -> {
                val attributionSourceClass = Class.forName("android.content.AttributionSource")
                val builderClass = Class.forName("android.content.AttributionSource\$Builder")
                val myUid = android.os.Process.myUid()
                val builderCtor = builderClass.getConstructor(Int::class.javaPrimitiveType)
                val builder = builderCtor.newInstance(myUid)
                builderClass.getMethod("setPackageName", String::class.java).invoke(builder, callingPkg)
                val attributionSource = builderClass.getMethod("build").invoke(builder)
                val m = providerClass.getMethod(
                    "call",
                    attributionSourceClass, String::class.java, String::class.java, String::class.java, Bundle::class.java,
                )
                m.invoke(provider, attributionSource, authority, method, arg, extras) as Bundle?
            }
            Build.VERSION.SDK_INT >= 30 -> {
                val m = providerClass.getMethod(
                    "call",
                    String::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java,
                )
                m.invoke(provider, callingPkg, null, authority, method, arg, extras) as Bundle?
            }
            Build.VERSION.SDK_INT >= 29 -> {
                val m = providerClass.getMethod(
                    "call",
                    String::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java,
                )
                m.invoke(provider, callingPkg, authority, method, arg, extras) as Bundle?
            }
            else -> {
                val m = providerClass.getMethod(
                    "call",
                    String::class.java, String::class.java, String::class.java, Bundle::class.java,
                )
                m.invoke(provider, callingPkg, method, arg, extras) as Bundle?
            }
        }
    }

    /**
     * [authority] 로 등록된 앱 프로세스의 ContentProvider 에 `call(method, extras)` 를 걸어
     * [extras] 를 그대로 전달한다. 반환값(Bundle)은 받되 지금은 안 씀 — 호출 성공 자체가
     * "Binder를 앱이 받았다"는 신호다(수신측이 실패하면 예외가 올라온다).
     */
    fun callProvider(authority: String, callingPkg: String, method: String, extras: Bundle) {
        val provider = getContentProviderExternal(authority, 0, null, authority)
            ?: error("content provider not found: $authority (앱이 안 켜져 있거나 provider 등록이 안 됐을 수 있음)")
        callOnProvider(provider, callingPkg, authority, method, null, extras)
    }
}
