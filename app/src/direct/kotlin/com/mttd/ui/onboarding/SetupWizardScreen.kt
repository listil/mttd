package com.mttd.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.data.adb.DirectAdbManager
import kotlinx.coroutines.delay

/**
 * `direct` flavor 전용 설정 마법사 — Shizuku 설치 단계가 통째로 없다(애초에 안 씀). 무선
 * 디버깅 페어링(자동화 불가 — 사람이 코드 입력) + 오버레이 권한, 2단계뿐.
 */
@Composable
fun SetupWizardScreen(manager: DirectAdbManager, onFinished: () -> Unit) {
    val context = LocalContext.current
    val connected by manager.ready.collectAsStateWithLifecycle()
    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val v = Settings.canDrawOverlays(context)
            if (v != canDraw) canDraw = v
        }
    }

    Scaffold { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val safePadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection).coerceAtLeast(0.dp),
            top = padding.calculateTopPadding().coerceAtLeast(0.dp),
            end = padding.calculateEndPadding(layoutDirection).coerceAtLeast(0.dp),
            bottom = padding.calculateBottomPadding().coerceAtLeast(0.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(safePadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "mTTD (direct)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFinished) { Text("건너뛰기") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. 무선 디버깅 페어링", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "① 개발자 옵션에서 무선 디버깅을 켜세요. 개발자 옵션이 안 보이면 " +
                            "설정 → 휴대전화 정보 → 빌드번호를 7번 연달아 탭하면 나타납니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("개발자 옵션 열기") }
                    Text(
                        "② \"무선 디버깅\" → \"페어링 코드로 기기 페어링\"을 눌러 뜨는 정보를 아래에 입력하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            DirectAdbStatusCard(manager)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. 오버레이 권한 허용", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "게임 화면 위에 수익 HUD를 띄우려면 \"다른 앱 위에 표시\" 권한이 필요합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (canDraw) {
                        Text("✅ 권한 확인됨", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("권한 설정 열기") }
                    }
                }
            }

            Button(onClick = onFinished, enabled = connected && canDraw) { Text("시작하기") }
        }
    }
}
