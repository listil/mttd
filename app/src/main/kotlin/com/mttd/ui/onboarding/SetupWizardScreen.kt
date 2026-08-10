package com.mttd.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mttd.data.shizuku.ShizukuState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

private enum class WizardStep(val order: Int) {
    SHIZUKU_INSTALL(0),
    SHIZUKU_RUNNING(1),
    PERMISSION(2),
    OVERLAY(3),
    DONE(4),
}

private fun WizardStep.isSatisfied(state: ShizukuState, canDraw: Boolean): Boolean = when (this) {
    WizardStep.SHIZUKU_INSTALL -> state.installed
    WizardStep.SHIZUKU_RUNNING -> state.binderAlive
    WizardStep.PERMISSION -> state.ready
    WizardStep.OVERLAY -> canDraw
    WizardStep.DONE -> true
}

/**
 * 최초 설정을 5단계로 안내하는 마법사.
 *
 * 안드로이드 정책상 무선 디버깅 페어링 화면은 자동화할 수 없어 완전 원클릭은 불가능하지만,
 * 그 외에는 각 단계 완료를 [ShizukuManager.state]/[Settings.canDrawOverlays] 로 자동 감지해서
 * 다음 단계로 넘어간다. 이미 일부/전부 준비된 상태로 들어오면(재설치 등) 충족 안 된 첫
 * 단계부터 시작 — 1번부터 스치듯 지나가지 않는다.
 */
@Composable
fun SetupWizardScreen(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current

    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val v = Settings.canDrawOverlays(context)
            if (v != canDraw) canDraw = v
        }
    }

    var step by rememberSaveable {
        mutableStateOf(WizardStep.entries.firstOrNull { !it.isSatisfied(state, canDraw) } ?: WizardStep.DONE)
    }
    // 사용자가 "이전"/"다음"을 한 번이라도 직접 누르면 true — 그 뒤로는 이미 완료된 단계에
    // 있어도 자동으로 건너뛰지 않고 사용자가 머문 자리를 존중한다(뒤로가서 확인하는 중일 수 있음).
    var manualNav by rememberSaveable { mutableStateOf(false) }

    // LaunchedEffect(step) 안에서 매개변수 state 를 그냥 읽으면 최초 캡처값에 고정된다 —
    // rememberUpdatedState 로 감싸야 snapshotFlow 가 최신 값 변화를 계속 관찰한다.
    val latestState = rememberUpdatedState(state)

    // 현재 보고 있는 단계에 한해서만 자동 진행 (edge-triggered).
    LaunchedEffect(step, manualNav) {
        if (step == WizardStep.DONE) return@LaunchedEffect
        if (step.isSatisfied(latestState.value, canDraw)) {
            // 이미 충족된 채로 이 단계에 있음. manualNav 전이면(=최초 진입 계산이 근소한
            // 레이스로 이미 끝난 단계에 멈춘 경우) 계속 건너뛴다. manualNav 이후엔 사용자가
            // 일부러 뒤로 가서 보는 중일 수 있으니 가만히 둔다.
            if (!manualNav) step = WizardStep.entries[step.order + 1]
            return@LaunchedEffect
        }
        snapshotFlow { step.isSatisfied(latestState.value, canDraw) }.first { it }
        step = WizardStep.entries[step.order + 1]
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
                    if (step == WizardStep.DONE) "설정 완료" else "${step.order + 1} / ${WizardStep.entries.size - 1} 단계",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (step != WizardStep.DONE) {
                    TextButton(onClick = onFinished) { Text("건너뛰기") }
                }
            }

            when (step) {
                WizardStep.SHIZUKU_INSTALL -> ShizukuInstallStep(installed = state.installed)
                WizardStep.SHIZUKU_RUNNING -> ShizukuRunningStep()
                WizardStep.PERMISSION -> PermissionStep(onRequestPermission = onRequestPermission)
                WizardStep.OVERLAY -> OverlayStep(canDraw = canDraw)
                WizardStep.DONE -> DoneStep()
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step != WizardStep.SHIZUKU_INSTALL && step != WizardStep.DONE) {
                    OutlinedButton(onClick = {
                        manualNav = true
                        step = WizardStep.entries[step.order - 1]
                    }) { Text("이전") }
                }
                if (step == WizardStep.DONE) {
                    Button(onClick = onFinished) { Text("시작하기") }
                } else {
                    val isLastBeforeDone = step.order == WizardStep.entries.size - 2
                    Button(onClick = {
                        manualNav = true
                        step = if (isLastBeforeDone) WizardStep.DONE else WizardStep.entries[step.order + 1]
                    }) { Text("다음") }
                }
            }
        }
    }
}

@Composable
private fun WizardCard(title: String, body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            body()
        }
    }
}

@Composable
private fun ShizukuInstallStep(installed: Boolean) {
    val context = LocalContext.current
    WizardCard(title = "1. Shizuku 앱 설치") {
        Text(
            "안드로이드 11부터는 일반 앱이 다른 앱의 파일을 못 읽어서, mTTD는 Shizuku라는 " +
                "도구로 게임 로그를 대신 읽습니다. 먼저 Shizuku 앱을 설치하세요 (root 불필요).",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (installed) {
            Text("✅ 설치 확인됨", color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { launchShizukuApp(context) }) { Text("Shizuku 앱 열기") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openShizukuPlayStore(context) }) { Text("Play 스토어에서 설치") }
                OutlinedButton(onClick = { openShizukuGithub(context) }) { Text("GitHub 릴리스에서 설치") }
            }
        }
    }
}

@Composable
private fun ShizukuRunningStep() {
    val context = LocalContext.current
    WizardCard(title = "2. Shizuku 실행") {
        Text(
            "① 개발자 옵션에서 무선 디버깅을 켜세요. 개발자 옵션이 안 보이면 " +
                "설정 → 휴대전화 정보 → 빌드번호를 7번 연달아 탭하면 나타납니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { openDeveloperOptions(context) }) { Text("개발자 옵션 열기") }
        Text(
            "② Shizuku 앱을 열고 \"무선 디버깅으로 시작\"을 눌러 페어링하세요. " +
                "이 화면은 안드로이드가 자동화를 막아둬서 직접 눌러야 합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { launchShizukuApp(context) }) { Text("Shizuku 앱 열기") }
        Text(
            "⚠️ 기기를 재부팅하면 Shizuku가 꺼집니다. 그때마다 Shizuku 앱에서 다시 시작해야 해요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionStep(onRequestPermission: () -> Unit) {
    WizardCard(title = "3. mTTD에 권한 허용") {
        Text(
            "Shizuku가 준비됐습니다. 아래 버튼을 누르면 Shizuku가 mTTD에 권한을 줄지 " +
                "묻는 팝업이 뜹니다 — 허용을 눌러주세요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequestPermission) { Text("권한 요청") }
    }
}

@Composable
private fun OverlayStep(canDraw: Boolean) {
    val context = LocalContext.current
    WizardCard(title = "4. 오버레이 권한 허용") {
        Text(
            "게임 화면 위에 수익 HUD를 띄우려면 \"다른 앱 위에 표시\" 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (canDraw) {
            Text("✅ 권한 확인됨", color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }) { Text("권한 설정 열기") }
        }
    }
}

@Composable
private fun DoneStep() {
    WizardCard(title = "🎉 설정 완료") {
        Text(
            "이제 게임을 실행하고, 인벤토리에서 정렬 버튼을 한 번 눌러주세요 " +
                "(가방 상태를 처음부터 알아야 수익 집계를 시작할 수 있어요). " +
                "화면 구석에 원형 배지가 뜨면 준비된 겁니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun launchShizukuApp(context: android.content.Context) {
    val i = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    if (i != null) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    } else {
        openShizukuPlayStore(context)
    }
}

private fun openShizukuPlayStore(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openShizukuGithub(context: android.content.Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun openDeveloperOptions(context: android.content.Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
