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
import com.mttd.ui.onboarding.OnboardingScreen
import com.mttd.ui.onboarding.SetupWizardScreen
import com.mttd.ui.onboarding.ShizukuStatusCard
import com.mttd.ui.theme.mTTDTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 안드로이드 13+ 는 알림 표시 자체에 런타임 권한이 필요하다 — 이게 없으면
        // "로그 감시 중" 포그라운드 서비스 알림이 조용히 안 뜬다. 매니페스트에
        // <uses-permission> 만 선언해두고 여기서 요청을 안 하고 있던 게 원인이었다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val shizuku = TrackerApplication.instance.accessManager

        setContent {
            mTTDTheme {
                val prefs = remember { OverlayPrefs(applicationContext) }
                val scope = rememberCoroutineScope()
                // null = DataStore 첫 읽기 전. false 로 기본값을 줘버리면 복귀 유저에게
                // 마법사가 1프레임 플래시되므로, 값이 오기 전엔 빈 화면(테마 배경만)으로 대기.
                // .collect (continuous) 를 쓰는 이유: 이 Activity/Compose 트리가 앱 생애주기
                // 내내 유지되는 유일한 화면이라, 마법사 완료/재오픈이 재구동 없이 반영돼야 함.
                val wizardCompleted by produceState<Boolean?>(initialValue = null, prefs) {
                    prefs.wizardCompleted.collect { value = it }
                }
                val state by shizuku.state.collectAsStateWithLifecycle()

                Surface(color = MaterialTheme.colorScheme.background) {
                    when (val done = wizardCompleted) {
                        null -> {}
                        else -> if (done) {
                            OnboardingScreen(
                                ready = state.ready,
                                userService = { shizuku.service },
                                onReopenWizard = { scope.launch { prefs.setWizardCompleted(false) } },
                                statusContent = {
                                    ShizukuStatusCard(
                                        state = state,
                                        onRequestPermission = { shizuku.requestPermissionOrBind() },
                                    )
                                },
                            )
                        } else {
                            SetupWizardScreen(
                                state = state,
                                onRequestPermission = { shizuku.requestPermissionOrBind() },
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
        val shizuku = TrackerApplication.instance.accessManager
        // 다른 앱에서 돌아왔을 때 Shizuku 상태 재조회 (권한이 방금 grant 됐을 수 있음).
        shizuku.requestPermissionOrBind()
        // Shizuku 준비되면 폴러 자동 시작 (이미 실행 중이면 no-op)
        autoStartTracker()
    }

    /**
     * 서비스를 띄우기만 하고, Shizuku 대기 · 게임 패키지 탐색 · 폴러 시작은 서비스에 맡긴다.
     *
     * 예전에는 여기서 `if (!shizuku.state.value.ready) return` 으로 조기 종료했는데,
     * Shizuku 바인딩이 비동기라 **첫 onResume 에서는 거의 항상 not-ready** 였다.
     * 그래서 앱을 한 번 나갔다 들어와야(두 번째 onResume) 트래킹이 시작됐다.
     */
    private fun autoStartTracker() {
        val app = TrackerApplication.instance
        if (app.trackerService.value?.status?.value?.active == true) return
        TrackerForegroundService.startSelfManaged(this)
    }
}
