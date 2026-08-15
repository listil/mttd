package com.mttd.ui.onboarding

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.data.adb.DirectAdbManager

/**
 * `direct` flavor의 [OnboardingScreen] 상태 카드 슬롯 채움이.
 *
 * IP/포트를 사람이 입력하지 않는다 — mDNS로 자동 탐지([com.mttd.data.adb.AdbMdns]) 하고,
 * 페어링 코드만 알림(RemoteInput)으로 받는다 ([com.mttd.service.DirectAdbPairingService]).
 * 설정 앱의 페어링 화면을 벗어나면 그 세션이 끊기기 때문에, 화면 전환 자체가 없어야 한다.
 */
@Composable
fun DirectAdbStatusCard(manager: DirectAdbManager) {
    val connected by manager.ready.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val lastError by manager.lastError.collectAsStateWithLifecycle()
    // false로 고정 — !connected 를 초기값으로 넣으면 "연결 안 됨" 상태로 펼쳐진 채 시작했다가
    // 백그라운드 재연결이 성공해도 expanded 가 그대로 남아있어("✅ 연결됨" 헤더 아래에 낡은
    // "페어링 서비스 찾는 중..." 문구가 계속 보이는) 혼란을 준다. connected 가 바뀔 때마다
    // 아래 if(expanded || !connected) 가 항상 최신 값을 보게 두는 편이 맞다.
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth().padding(0.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (connected) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✅ 무선 adb 연결됨", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "접기" else "펼치기",
                    )
                }
            } else {
                Text("❌ 무선 adb 연결 안 됨", fontWeight = FontWeight.SemiBold)
            }

            if (expanded || !connected) {
                Text(
                    "① 무선 디버깅이 꺼져있다면 아래에서 개발자 옵션을 먼저 한 번 열어 켜주세요.\n" +
                        "② 아래 \"페어링 시작\"을 눌러 검색을 켜두세요 (이 상태로 대기).\n" +
                        "③ 그 다음 개발자 옵션 → 무선 디버깅 → \"페어링 코드로 기기 페어링\"을 여세요 — " +
                        "이 화면에서 벗어나면 페어링이 끊기니, 여기서 화면을 안 바꾸고 그대로 두세요.\n" +
                        "④ 알림 서랍을 내려서 뜬 알림에 화면의 6자리 코드를 입력하면 자동으로 연결됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 연결된 뒤엔 파일 접근이 순수 Binder(DirectDaemonStarter가 shell UID로 띄운
                // 상주 프로세스)로 넘어가서 WiFi를 꺼도(LTE 등) 계속 동작한다 — 다만 그 상주
                // 프로세스는 기기가 재부팅되면(또는 강제 종료되면) 같이 사라지므로, 그 다음엔
                // 이 페어링/연결 과정을 다시 거쳐야 한다(그 순간엔 WiFi 필요).
                Text(
                    "※ 한 번 연결되면 이후엔 WiFi 없이(LTE 등) 계속 동작합니다 — 기기를 재부팅했을 때만" +
                        " WiFi에서 다시 연결해주면 됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val statusText = when (status) {
                    DirectAdbManager.Status.IDLE -> null
                    DirectAdbManager.Status.SEARCHING -> "페어링 서비스 찾는 중..."
                    DirectAdbManager.Status.WAITING_FOR_CODE -> "알림에서 코드를 입력해주세요"
                    DirectAdbManager.Status.PAIRING -> "페어링 중..."
                    DirectAdbManager.Status.CONNECTING -> "연결 시도 중..."
                    DirectAdbManager.Status.CONNECTED -> "연결됨"
                    DirectAdbManager.Status.FAILED -> "실패"
                }
                val busy = status in setOf(
                    DirectAdbManager.Status.SEARCHING,
                    DirectAdbManager.Status.PAIRING,
                    DirectAdbManager.Status.CONNECTING,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) manager.startPairing()
                        },
                        enabled = !busy,
                    ) { Text("페어링 시작") }
                    if (busy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    }
                }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("개발자 옵션 열기") }
                statusText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                lastError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
