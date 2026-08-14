package com.mttd

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mttd.data.adb.DirectAdbManager
import com.mttd.diagnostics.CrashLogger
import com.mttd.diagnostics.DiagnosticLog
import com.mttd.service.TrackerForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

class TrackerApplication : Application(), ImageLoaderFactory {

    lateinit var accessManager: DirectAdbManager
        private set

    private val _trackerService = MutableStateFlow<TrackerForegroundService?>(null)
    val trackerService: StateFlow<TrackerForegroundService?> = _trackerService.asStateFlow()

    fun setTrackerService(svc: TrackerForegroundService?) { _trackerService.value = svc }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        instance = this
        DiagnosticLog.log(this, "App", "onCreate ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) [direct]")
        accessManager = DirectAdbManager(this).also { it.start() }

        // 디버그 빌드에서만 — 이미 페어링된 셸 연결로 실제 logcat 을 떠서 진단 로그에 붙인다.
        // release APK 는 이 줄 자체가 없으니(BuildConfig.DEBUG 상수로 아예 분기) 별도 토글 불필요.
        if (BuildConfig.DEBUG) {
            DiagnosticLog.extraSection = { accessManager.captureLogcatText() }
        }
    }

    /**
     * 아이템 아이콘 CDN(cdn.tlidb.com)이 hotlink 방지로 Referer 없는 요청을 403 처리한다.
     * Coil 의 싱글톤 ImageLoader 에 그 호스트로 가는 요청에만 Referer 를 붙이는 인터셉터를 심는다.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val withReferer = if (req.url.host.endsWith("tlidb.com")) {
                            req.newBuilder().header("Referer", "https://tlidb.com/").build()
                        } else req
                        chain.proceed(withReferer)
                    }
                    .build()
            }
            .build()

    companion object {
        lateinit var instance: TrackerApplication
            private set
    }
}
