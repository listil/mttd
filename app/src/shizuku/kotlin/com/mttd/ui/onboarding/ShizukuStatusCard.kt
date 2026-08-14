package com.mttd.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mttd.data.shizuku.ShizukuState

/** `shizuku` flavor의 [OnboardingScreen] 상태 카드 슬롯 채움이 (4단계 Shizuku 상태 표시). */
@Composable
fun ShizukuStatusCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 다 준비된 상태에선 매번 4줄 상세를 보여줄 필요가 없어 한 줄 요약으로 접어둔다.
    // 뭔가 문제가 있을 땐(하나라도 false) 바로 뭘 고쳐야 하는지 보여야 하니 항상 펼쳐둔다.
    val collapsible = state.ready
    Card(modifier = Modifier.fillMaxWidth().padding(0.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (collapsible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "✅ Shizuku 준비 완료",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "접기" else "펼치기",
                    )
                }
            } else {
                Text("Shizuku 상태", fontWeight = FontWeight.SemiBold)
            }
            if (expanded || !collapsible) {
                StatusRow("설치됨", state.installed)
                StatusRow("바인더 살아있음", state.binderAlive)
                StatusRow("권한 허용", state.permission)
                StatusRow("UserService 바인딩", state.userServiceBound)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRequestPermission,
                    enabled = state.installed,
                ) {
                    Text(
                        when {
                            !state.installed -> "Shizuku 미설치 (재확인)"
                            !state.binderAlive -> "Shizuku 실행 후 재확인"
                            !state.permission -> "권한 요청"
                            !state.userServiceBound -> "UserService 재바인딩"
                            else -> "이미 준비됨"
                        }
                    )
                }
            }
        }
    }
}
