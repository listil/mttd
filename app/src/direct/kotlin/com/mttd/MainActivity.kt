package com.mttd

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.data.prefs.OverlayPrefs
import com.mttd.service.TrackerForegroundService
import com.mttd.ui.onboarding.DirectAdbStatusCard
import com.mttd.ui.onboarding.OnboardingScreen
import com.mttd.ui.onboarding.SetupWizardScreen
import com.mttd.ui.theme.mTTDTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 안드로이드 13+ 는 알림 표시 자체에 런타임 권한이 필요하다 — 이게 없으면 페어링
        // 코드 입력 알림은 물론, 기존 "로그 감시 중" 포그라운드 서비스 알림까지 조용히 안 뜬다.
        // 매니페스트에 <uses-permission> 만 선언해두고 여기서 요청을 안 하고 있던 게 원인이었다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val access = TrackerApplication.instance.accessManager

        setContent {
            mTTDTheme {
                val prefs = remember { OverlayPrefs(applicationContext) }
                val scope = rememberCoroutineScope()
                val wizardCompleted by produceState<Boolean?>(initialValue = null, prefs) {
                    prefs.wizardCompleted.collect { value = it }
                }
                val ready by access.ready.collectAsStateWithLifecycle()

                Surface(color = MaterialTheme.colorScheme.background) {
                    when (val done = wizardCompleted) {
                        null -> {}
                        else -> if (done) {
                            OnboardingScreen(
                                ready = ready,
                                userService = { access.service },
                                onReopenWizard = { scope.launch { prefs.setWizardCompleted(false) } },
                                statusContent = { DirectAdbStatusCard(access) },
                            )
                        } else {
                            SetupWizardScreen(
                                manager = access,
                                onFinished = { scope.launch { prefs.setWizardCompleted(true) } },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val access = TrackerApplication.instance.accessManager
        // 저장된 연결이 있으면 조용히 재시도 (페어링 다이얼로그는 안 띄움).
        access.retryConnect()
        autoStartTracker()
    }

    /** 서비스를 띄우기만 하고, 연결 대기·게임 패키지 탐색·폴러 시작은 서비스에 맡긴다. */
    private fun autoStartTracker() {
        val app = TrackerApplication.instance
        if (app.trackerService.value?.status?.value?.active == true) return
        TrackerForegroundService.startSelfManaged(this)
    }
}
