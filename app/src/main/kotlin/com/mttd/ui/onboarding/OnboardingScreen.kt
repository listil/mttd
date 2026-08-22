package com.mttd.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mttd.IUserService
import com.mttd.TrackerApplication
import com.mttd.data.log.LogPoller
import com.mttd.domain.models.SessionState
import com.mttd.service.TrackerForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MainTab(val label: String) {
    EARNINGS("수익"),
    VALUE("가치"),
    SETTINGS("설정"),
}

/**
 * 앱 메인 화면 — **수익 집계**와 **옵션 설정**을 탭으로 분리.
 *
 * - 수익 탭: 세션 집계(경과·총 수익·시간당·픽업 목록) + 현재 시세 요약 한 줄
 * - 설정 탭: Shizuku · 오버레이 · 시세 출처 · 로그 프로브 · 로그 tail
 */
@Composable
fun OnboardingScreen(
    ready: Boolean,
    userService: () -> IUserService?,
    onReopenWizard: () -> Unit = {},
    statusContent: @Composable () -> Unit,
) {
    var tab by remember { mutableStateOf(MainTab.EARNINGS) }
    val context = LocalContext.current

    Scaffold { padding ->
        // 일부 기기(멀티윈도우/프리폼 모드 등)가 인셋을 음수로 내려주는 경우가 있어
        // Modifier.padding() 이 "Padding must be non-negative" 로 즉시 크래시한다 — 방어적으로 clamp.
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
                .padding(safePadding),
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "mTTD",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "v${com.mttd.BuildConfig.VERSION_NAME} (${com.mttd.BuildConfig.FLAVOR})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    UpdateCheckButton()
                }
                Text(
                    text = "Torchlight: Infinite 세션 트래커",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "github.com/listil/mttd",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val i = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/listil/mttd"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    },
                )
                Spacer(Modifier.height(12.dp))
                UpdateBanner()
            }

            TabRow(selectedTabIndex = tab.ordinal) {
                MainTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label) },
                    )
                }
            }

            // 탭마다 독립된 스크롤 위치를 갖도록 tab 이 바뀌면 새 ScrollState 로 교체 —
            // 하나의 ScrollState 를 공유하면 한 탭에서 내려놓은 스크롤 위치가 다른 탭에도
            // 그대로 남아 위쪽 카드들이 화면 밖으로 밀려 안 보이는 문제가 있었다.
            val scrollState = remember(tab) { androidx.compose.foundation.ScrollState(0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    MainTab.EARNINGS -> {
                        if (!ready) {
                            NotReadyNotice(onGoToSettings = { tab = MainTab.SETTINGS })
                        }
                        EarningsSummaryCard()
                        RunHistorySection()
                        PriceSummaryLine()
                    }
                    MainTab.VALUE -> {
                        ValueScreen()
                    }
                    MainTab.SETTINGS -> {
                        statusContent()
                        // 오버레이 권한은 특권 접근 계층과 무관(userService 안 씀)해서 게이트 밖으로 뺐다 —
                        // 안 그러면 재부팅 등으로 접근이 끊겼을 때 이미 켜둔 오버레이 설정을
                        // 확인/조정할 카드 자체가 통째로 사라져 보인다.
                        OverlayCard()
                        if (ready) {
                            PriceCard()
                            LoadoutExportCard()
                        } else {
                            Text(
                                text = "위 연결이 준비되면 나머지 설정이 활성화됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 진단 로그 공유는 연결이 안 된 상태(=페어링/바인딩 문제 진단 중)에서
                        // 오히려 더 필요하므로 ready 게이트 밖에 둔다.
                        AdvancedSection(userService = userService)
                        TextButton(onClick = onReopenWizard) { Text("설정 가이드 다시 보기") }
                        ContactCard()
                        ExitCard()
                    }
                }
            }
        }
    }
}

/**
 * 새 버전 알림 배너. 두 탭 모두 위에 뜬다.
 *
 * 확인은 서비스 시작 시 1회만 하고, 여기서는 결과만 표시한다.
 * 설치는 하지 않고 릴리스 페이지를 열어준다 ([UpdateChecker] 주석 참조).
 */
@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val update by (service?.availableUpdate
        ?: MutableStateFlow<com.mttd.data.update.UpdateChecker.Update?>(null))
        .collectAsStateWithLifecycle()

    val u = update ?: return
    var dismissed by remember(u.versionName) { mutableStateOf(false) }
    if (dismissed) return
    // 기본은 접힘 — 새 버전이 있다는 것만 한 줄로 알리고, 자세한 내용은 탭해서 펼쳐 보게.
    var expanded by remember(u.versionName) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⬆️ 새 버전 ${u.versionName}",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "접기" else "펼치기",
                )
            }
            if (expanded) {
                Text(
                    "현재 ${com.mttd.BuildConfig.VERSION_NAME} (${com.mttd.BuildConfig.FLAVOR})" +
                        if (u.apkSizeBytes > 0) "  ·  APK %.1f MB".format(u.apkSizeBytes / 1024.0 / 1024.0) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (u.notes.isNotBlank()) {
                    // maxLines로 자르면 뒷부분이 그냥 잘려서 안 보였다 — 대신 높이를 제한하고
                    // 그 안에서 스크롤해서 전체 내용을 볼 수 있게 한다.
                    Text(
                        parseSimpleMarkdown(u.notes),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val i = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(u.releaseUrl),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }) { Text("릴리스 열기") }
                    OutlinedButton(onClick = { dismissed = true }) { Text("나중에") }
                }
            }
        }
    }
}

/**
 * "**굵게**" 만 지원하는 아주 단순한 마크다운 파서.
 *
 * GitHub 릴리스 노트는 "- ✨ **제목**: 설명" 형식만 쓰므로 이 정도면 충분하고,
 * 마크다운 라이브러리를 새로 추가할 이유는 없다.
 */
private fun parseSimpleMarkdown(text: String): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("**", i)
            if (start == -1) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, start))
            val end = text.indexOf("**", start + 2)
            if (end == -1) {
                append(text.substring(start))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(start + 2, end))
            }
            i = end + 2
        }
    }

@Composable
private fun NotReadyNotice(onGoToSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("⚠️ Shizuku 준비 안 됨", fontWeight = FontWeight.SemiBold)
            Text(
                "로그를 읽을 수 없어 집계가 시작되지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onGoToSettings) { Text("설정으로 이동") }
        }
    }
}

/** 수익 탭 맨 위 — 수익 숫자만. 세션 제어/상태는 설정 탭. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EarningsSummaryCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val session by (service?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    val prefs = remember(context) { com.mttd.data.prefs.OverlayPrefs(context.applicationContext) }
    val timeBasisId by prefs.timeBasisId.collectAsStateWithLifecycle(
        initialValue = com.mttd.domain.models.TimeBasis.DEFAULT.id,
    )
    val basis = com.mttd.domain.models.TimeBasis.fromId(timeBasisId)

    val ticking = session.active && !session.paused && session.baselineReady
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ticking) { while (ticking) { kotlinx.coroutines.delay(1000); tick++ } }
    val elapsed = remember(session.startedAtMs, session.paused, session.runs, basis, tick) { basis.elapsedMs(session) }
    val perHour = remember(session.totalValue, session.paused, session.runs, basis, tick) { basis.incomePerHour(session) }
    val mapElapsed = remember(session.runs, session.paused, tick) { session.mapElapsedMs }
    val totalElapsed = remember(session.startedAtMs, session.paused, tick) { session.elapsedMs }
    val mapPerHour = remember(session.totalValue, session.runs, session.paused, tick) { session.mapIncomePerHour }
    val totalPerHour = remember(session.totalValue, session.paused, tick) { session.incomePerHour }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 좁은 화면에서 숫자 + 버튼 2개가 한 줄에 안 들어가면 버튼 묶음이 통째로
            // 다음 줄로 넘어간다. 버튼 묶음을 하나의 FlowRow 항목으로 묶고 SpaceBetween 을
            // 써야 (넓은 화면에서) 예전처럼 카드 오른쪽 끝에 붙는다 — 버튼 두 개를 각각
            // 별개 항목으로 두면 줄바꿈 안 될 때도 텍스트 바로 옆에 붙어버려서 안 예뻤다.
            var confirmReset by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column {
                    Text(
                        com.mttd.ui.overlay.formatFire(session.totalValue),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "총 수익" +
                            if (session.unpricedPickups > 0) "  ·  미가격 ${session.unpricedPickups}건" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 오버레이까지 안 가도 앱에서 바로 제어
                    OutlinedButton(onClick = { service?.togglePause() }, enabled = service != null) {
                        Text(if (session.paused) "▶ 재개" else "❚❚ 일시정지")
                    }
                    OutlinedButton(onClick = { confirmReset = true }, enabled = service != null) {
                        Text("리셋")
                    }
                }
                if (confirmReset) {
                    // 같은 자리에서 버튼만 바뀌는 인라인 확인은 빠르게 두 번 탭하면
                    // 바뀐 자리의 "확인"이 그대로 눌려버릴 수 있어 별도 팝업으로 분리.
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("세션을 리셋할까요?") },
                        text = { Text("지금까지 집계된 수익·회차 기록이 모두 사라집니다.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmReset = false
                                service?.resetSession()
                            }) { Text("리셋", color = Color(0xFFF87171)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmReset = false }) { Text("취소") }
                        },
                    )
                }
            }
            if (session.paused) {
                Text(
                    "일시정지 중 — 경과 시간과 집계가 멈춰 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                MiniStat("시간당 (${basis.shortLabel})", com.mttd.ui.overlay.formatFire(perHour) + " /h")
                Spacer(Modifier.width(20.dp))
                MiniStat("경과 (${basis.shortLabel})", formatElapsed(elapsed))
                Spacer(Modifier.width(20.dp))
                MiniStat("맵 진입", "${session.mapsEntered}")
                Spacer(Modifier.width(20.dp))
                MiniStat("픽업", "${session.pickupCount}")
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            val timeColWidth = 110.dp
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MiniStat("M 경과", formatElapsed(mapElapsed), modifier = Modifier.width(timeColWidth))
                    MiniStat("M 시간당", com.mttd.ui.overlay.formatFire(mapPerHour) + " /h")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MiniStat("T 경과", formatElapsed(totalElapsed), modifier = Modifier.width(timeColWidth))
                    MiniStat("T 시간당", com.mttd.ui.overlay.formatFire(totalPerHour) + " /h")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MiniStat("맵당 평균시간", formatElapsed(session.averageDurationPerMap), modifier = Modifier.width(timeColWidth))
                    MiniStat("맵당 평균수익", com.mttd.ui.overlay.formatFire(session.averageValuePerMap))
                }
            }
            if (!session.baselineReady) {
                Text(
                    "🎒 게임에서 로그 오픈 후 가방 정렬을 눌러주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

/**
 * 회차별 수익 그래프 + 선택 회차 상세 + 전체 아이템 집계.
 *
 * 그래프의 막대를 누르면 그 회차의 획득 아이템·시세를 볼 수 있고,
 * 잘못 집계된 회차는 상세에서 삭제할 수 있다 (세션 총합도 같이 재계산).
 */
@Composable
private fun RunHistorySection() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val svc = service
    val session by (svc?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    // 막대를 누르면 팝업. 선택한 회차가 삭제되면 자동으로 닫힌다.
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = session.runs.firstOrNull { it.id == selectedId }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("회차별 수익", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "막대를 누르면 상세",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${session.runs.size}회차",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RunBarChart(
                runs = session.runs,
                selectedId = selected?.id,
                onSelect = { selectedId = it },
            )
        }
    }

    TotalItemsCard(merged = session.sessionItems)

    selected?.let { run ->
        RunDetailDialog(
            run = run,
            loadItems = { id -> svc?.loadRunItems(id) ?: emptyList() },
            onDelete = { id -> svc?.deleteRun(id) },
            onDismiss = { selectedId = null },
        )
    }
}

/** 수익 탭 하단에 붙는 시세 요약 한 줄. 자세한 설정은 설정 탭. */
@Composable
private fun PriceSummaryLine() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val priceState by (service?.priceState ?: MutableStateFlow(com.mttd.data.prices.PriceRepository.State()))
        .collectAsStateWithLifecycle()

    val text = when {
        priceState.loading -> "시세 불러오는 중..."
        priceState.itemsWithPrice <= 1 -> "시세 없음 — 설정 탭에서 새로고침"
        else -> "시세: ${priceState.source.label} · ${priceState.seasonId ?: "-"} · " +
                "가격 ${priceState.itemsWithPrice}개 · ${formatUpdatedAgo(priceState.lastUpdatedMs)}"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Shizuku 바인딩처럼 유저 쪽에서 재현은 되는데 개발자가 adb로 못 보는 문제를 리포트받기 위한
 * 버튼. [DiagnosticLog] 가 계속 누적해둔 상태 전이 기록을 시스템 공유 시트로 넘겨
 * 카카오톡 등으로 바로 전달할 수 있게 한다. shizuku/direct 양쪽 상태 카드가 공용으로 쓴다.
 */
@Composable
fun DiagnosticLogButton() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    TextButton(
        onClick = {
            sending = true
            scope.launch {
                val intent = com.mttd.diagnostics.DiagnosticLog.buildShareIntent(context)
                sending = false
                context.startActivity(intent)
            }
        },
        enabled = !sending,
    ) { Text(if (sending) "로그 준비 중..." else "📋 진단 로그 보내기") }
}

@Composable
fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⭕")
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 로그 파일 프로브 / 폴링 raw 상태 — 일반 유저에겐 의미 없는 개발자 진단 정보라
 * 기본 접힘 상태로 숨겨두고, 필요할 때만 펼쳐서 본다.
 */
@Composable
private fun AdvancedSection(userService: () -> IUserService?) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("고급", fontWeight = FontWeight.SemiBold)
                    Text(
                        "로그 파일 진단 · 폴링 상태 (개발자용)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "접기" else "펼치기",
                )
            }
            if (expanded) {
                DiagnosticLogButton()
                ProbeCard(userService = userService)
                LogTailCard(userService = userService)
            }
        }
    }
}

@Composable
private fun ProbeCard(userService: () -> IUserService?) {
    var installedGames by remember { mutableStateOf("(조회 중...)") }
    var logSize by remember { mutableStateOf("(조회 중...)") }
    var logPath by remember { mutableStateOf("(대기)") }

    LaunchedEffect(Unit) {
        val svc = userService() ?: run {
            logSize = "UserService 없음"
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val packages = try {
                svc.listInstalledGamePackages().lines().filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
            installedGames = if (packages.isEmpty())
                "감지된 게임 없음"
            else packages.joinToString(", ")

            val path = packages.firstOrNull()?.let {
                "/sdcard/Android/data/$it/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
            }
            if (path == null) {
                logPath = "(게임 미감지)"
                logSize = "-"
                return@withContext
            }
            logPath = path
            val size = try { svc.getFileSize(path) } catch (e: Exception) { -1L }
            logSize = if (size < 0) "파일 없음 또는 접근 불가"
                      else "%,d bytes (%.2f MB)".format(size, size / 1024.0 / 1024.0)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("로그 파일 프로브", fontWeight = FontWeight.SemiBold)
            LabelValue("감지된 게임", installedGames)
            LabelValue("로그 경로", logPath)
            LabelValue("파일 크기", logSize)
        }
    }
}

@Composable
private fun LogTailCard(userService: () -> IUserService?) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()

    // 감지된 로그 경로 (게임 자동 감지)
    var detectedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val svc = userService() ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val pkg = try {
                svc.listInstalledGamePackages().lines().firstOrNull { it.isNotBlank() }
            } catch (_: Exception) { null }
            detectedPath = pkg?.let {
                "/sdcard/Android/data/$it/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("로그 폴링 (M1)", fontWeight = FontWeight.SemiBold)

            val svc = service
            val status by (svc?.status ?: MutableStateFlow(LogPoller.PollingStatus()))
                .collectAsStateWithLifecycle()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val path = detectedPath ?: return@Button
                        TrackerForegroundService.start(context, path)
                    },
                    enabled = !status.active && detectedPath != null,
                ) { Text("시작") }
                OutlinedButton(
                    onClick = { TrackerForegroundService.stop(context) },
                    enabled = status.active,
                ) { Text("중지") }
            }

            LabelValue("폴링 활성", if (status.active) "예" else "아니오")
            LabelValue("현재 간격", "${status.intervalMs} ms")
            LabelValue("파일 크기", if (status.fileSize < 0) "-" else "%,d bytes".format(status.fileSize))
            LabelValue("현재 offset", "%,d bytes".format(status.offset))
            LabelValue(
                "총 read / 라인",
                "%,d bytes / %,d 라인".format(status.totalBytesRead, status.totalLinesEmitted)
            )
            status.lastError?.let { LabelValue("최근 오류", it) }

            HorizontalDivider()
            Text("최근 라인 (10개)", fontWeight = FontWeight.SemiBold)
            RecentLines(svc?.lines)
        }
    }
}

@Composable
private fun RecentLines(flow: SharedFlow<String>?) {
    val recent = remember { mutableStateOf(ArrayDeque<String>()) }
    LaunchedEffect(flow) {
        if (flow == null) return@LaunchedEffect
        launch {
            flow.collect { line ->
                val dq = recent.value
                dq.addLast(line)
                while (dq.size > 10) dq.removeFirst()
                // trigger recomposition — replace with a copy
                recent.value = ArrayDeque(dq)
            }
        }
    }
    val lines = recent.value
    if (lines.isEmpty()) {
        Text("(아직 없음)", style = MaterialTheme.typography.bodySmall)
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF0F172A).copy(alpha = 0.85f),
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (line in lines) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE2E8F0),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun OverlayCard() {
    val context = LocalContext.current
    var canDraw by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val prefs = remember(context) { com.mttd.data.prefs.OverlayPrefs(context.applicationContext) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val badgeMetricId by prefs.badgeIncomeMetric.collectAsStateWithLifecycle(
        initialValue = com.mttd.ui.overlay.BadgeIncomeMetric.DEFAULT.id,
    )
    val timeBasisId by prefs.timeBasisId.collectAsStateWithLifecycle(
        initialValue = com.mttd.domain.models.TimeBasis.DEFAULT.id,
    )

    // 앱이 다시 포그라운드로 올 때 권한 상태 재확인
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val v = android.provider.Settings.canDrawOverlays(context)
            if (v != canDraw) canDraw = v
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("오버레이 (M3)", fontWeight = FontWeight.SemiBold)
            StatusRow("SYSTEM_ALERT_WINDOW 권한", canDraw)

            if (!canDraw) {
                Button(onClick = {
                    val i = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }) { Text("권한 설정 열기") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        com.mttd.service.TrackerForegroundService.showOverlay(context)
                    }) { Text("오버레이 표시") }
                    OutlinedButton(onClick = {
                        com.mttd.service.TrackerForegroundService.hideOverlay(context)
                    }) { Text("숨기기") }
                }
                Text(
                    "표시되면 좌상단 근처에 시계 아이콘이 뜹니다. 탭 → HUD 토글, 드래그 → 이동.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text(
                "경과·시간당 수익 기준 (수익 탭·플로팅 HUD·배지 공용)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TimeBasisSelector(
                current = com.mttd.domain.models.TimeBasis.fromId(timeBasisId),
                onSelect = { b -> scope.launch { prefs.setTimeBasisId(b.id) } },
            )

            HorizontalDivider()
            Text(
                "배지 2번째 줄 표시값",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BadgeMetricSelector(
                current = com.mttd.ui.overlay.BadgeIncomeMetric.fromId(badgeMetricId),
                onSelect = { m -> scope.launch { prefs.setBadgeIncomeMetric(m.id) } },
            )
        }
    }
}

/**
 * 수익 탭 헤드라인·플로팅 HUD·배지(아이콘 오버레이) 1번째 줄의 "경과"/"시간당" 이
 * M(매핑)·T(토탈) 중 어느 시간 기준을 쓸지 선택 — 셋이 이 값 하나를 공유한다.
 * 수익 탭은 이 선택과 무관하게 M/T 둘 다 항상 같이 보여준다 — 이건 그 중 대표로 내세울
 * 하나(헤드라인·HUD·배지)를 고르는 것.
 */
@Composable
private fun TimeBasisSelector(
    current: com.mttd.domain.models.TimeBasis,
    onSelect: (com.mttd.domain.models.TimeBasis) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in com.mttd.domain.models.TimeBasis.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = current == b, onClick = { onSelect(b) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == b, onClick = { onSelect(b) })
                Text(b.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 배지(아이콘 오버레이) 2번째 줄에 어떤 수익 지표를 보여줄지 선택. */
@Composable
private fun BadgeMetricSelector(
    current: com.mttd.ui.overlay.BadgeIncomeMetric,
    onSelect: (com.mttd.ui.overlay.BadgeIncomeMetric) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (m in com.mttd.ui.overlay.BadgeIncomeMetric.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = current == m, onClick = { onSelect(m) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == m, onClick = { onSelect(m) })
                Text(m.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * 캐릭터 장비/스킬/석판/천명/핵심 재능/프리즘 정보를 미니 토치DB로 내보내는 카드.
 *
 * [CharacterLoadoutTracker] 는 로그인 직후 한 번 오는 `GetPlayerData` 전체 동기화를 관측해야
 * 채워지므로, 앱을 켠 뒤로 게임에 재접속한 적이 없으면 비어 있을 수 있다 — 그 경우 안내만 하고
 * 버튼은 계속 눌러볼 수 있게 둔다(재시도 비용이 없으므로).
 *
 * 2026-08-14부터 [com.mttd.data.export.LogRelayClient] 로 원본 로그 슬라이스(`GetPlayerData`
 * 시작 지점부터 현재 파일 끝까지, [TrackerForegroundService.currentLoadoutExportBlock] 참조)를
 * 그대로 올리고, 서버가 돌려준 1회용 URL을 여는 방식이다 — 필드별 추출([Mli1Codec] 의
 * `logimport=` URL 방식)은 [CharacterLoadoutTracker] 클래스 doc의 이유로 폐기했다. 이 앱이
 * mini-tlidb.winterer.workers.dev 로 직접 통신하는 유일한 지점이라 README/INSTALL 의 네트워크
 * 호스트 목록에 등재돼 있어야 한다.
 */
@Composable
private fun LoadoutExportCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val loadout by (service?.loadoutState ?: MutableStateFlow(null))
        .collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val relayClient = remember { com.mttd.data.export.LogRelayClient() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("캐릭터 장비 내보내기", fontWeight = FontWeight.SemiBold)
            Text(
                "캐릭터 빌드 정보를 미니 토치DB로 보냅니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loadout == null) {
                Text(
                    "① 로그 오픈 → ② 게임에서 로그아웃 후 재접속",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                LoadoutPreview(loadout!!)
            }

            var sending by remember { mutableStateOf(false) }
            var errorText by remember { mutableStateOf<String?>(null) }
            var copied by remember { mutableStateOf(false) }

            fun openUrl(url: String) {
                // FLAG_ACTIVITY_NEW_TASK 필요한 이유는 예전 saltedUrl() 시절과 동일 — 이 파일의
                // 다른 브라우저 오픈 인텐트(업데이트 다운로드 링크, GitHub 링크, 오버레이 권한
                // 설정)와 통일. 지금은 서버가 매번 새 토큰을 발급해 URL이 항상 달라지므로 클라이언트
                // 쪽에서 nonce 를 직접 만들 필요는 없어졌다.
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }

            /** 원본 슬라이스를 릴레이 서버에 올리고, 성공하면 [onSuccess] 에 발급받은 URL을 넘긴다. */
            fun relayThen(onSuccess: (String) -> Unit) {
                sending = true
                errorText = null
                scope.launch {
                    try {
                        val rawSlice = service?.currentLoadoutExportBlock()
                        if (rawSlice == null) {
                            errorText = "아직 원본 로그를 못 잡았습니다. 재접속 후 다시 시도해주세요."
                            return@launch
                        }
                        val result = relayClient.relay(rawSlice)
                        onSuccess(result.url)
                    } catch (e: Exception) {
                        errorText = "전송 실패: ${e.message ?: "네트워크 오류"} — 다시 시도해주세요."
                    } finally {
                        sending = false
                    }
                }
            }

            Button(
                enabled = loadout != null && !sending,
                onClick = { relayThen { url -> openUrl(url) } },
            ) { Text(if (sending) "전송 중..." else "미니 토치DB로 내보내기") }

            TextButton(
                enabled = loadout != null && !sending,
                onClick = {
                    relayThen { url ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("mini-tlidb logdump URL", url))
                        copied = true
                    }
                },
            ) { Text("(안 열리면) 주소만 복사해서 직접 붙여넣기") }

            if (copied) {
                Text(
                    "URL이 클립보드에 복사됐습니다. 브라우저에서 새 탭을 열고 주소창에 붙여넣어 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 실제로 뭐가 전송될지 미리 볼 수 있게 요약. 사용자가 "진짜 지금 장비가 맞는지" 브라우저를
 * 열기 전에 앱 안에서 확인할 수 있는 유일한 지점이다. 단, 실제로 서버에 올라가는 건 이 필드들이
 * 아니라 원본 로그 슬라이스([TrackerForegroundService.currentLoadoutExportBlock], `LogRelayClient`
 * 참조) 이라 — 파싱은 수신측이 따로 하므로, 이 미리보기는 "대략 이 정도 데이터가 담겨있다"는
 * 참고용이지 전송될 JSON의 정확한 내용은 아니다. 전송 성공/실패는 [LoadoutExportCard] 의
 * `errorText` 로 확인한다.
 */
@Composable
private fun LoadoutPreview(loadout: com.mttd.data.export.CharacterLoadout) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            loadout.char ?: "(이름 없음)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "장비 ${loadout.gear?.size ?: 0}개 · 스킬 ${loadout.skills?.size ?: 0}개 · " +
                "석판 노드 ${loadout.slate?.size ?: 0}개 · 천명 ${loadout.dst?.size ?: 0}개 · " +
                "핵심 재능 ${loadout.genius?.core?.size ?: 0}개 · " +
                "프리즘 ${if (loadout.prism != null) 1 else 0}개 · " +
                "히어로 추억 ${loadout.mems?.size ?: 0}개",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PriceCard() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val svc = service
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val priceState by (svc?.priceState ?: MutableStateFlow(com.mttd.data.prices.PriceRepository.State()))
        .collectAsStateWithLifecycle()
    val seasonMode by (svc?.priceRepository()?.seasonMode ?: MutableStateFlow(com.mttd.data.prices.SeasonMode.REGULAR))
        .collectAsStateWithLifecycle()
    val prefs = remember(context) { com.mttd.data.prefs.OverlayPrefs(context.applicationContext) }

    // 자동 fetch 는 TrackerForegroundService 가 담당한다 (앱 UI 를 안 열어도 시세가 필요하므로).
    // 여기서 또 부르면 시즌 스윕이 중복 실행되므로 수동 새로고침 버튼만 남긴다.

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("시세", fontWeight = FontWeight.SemiBold)

            Text(
                "가격 출처",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PriceSourceSelector(
                current = priceState.source,
                enabled = svc != null && !priceState.loading,
                onSelect = { src ->
                    val s = svc?.priceRepository() ?: return@PriceSourceSelector
                    scope.launch {
                        s.switchSource(src)
                        prefs.setPriceSourceId(src.id)
                    }
                },
            )

            // 시즌 모드는 ETOR 에서만 의미 있음 — TTD 는 시즌 개념 자체가 없다.
            if (priceState.source == com.mttd.data.prices.PriceSource.ETOR) {
                Text(
                    "시즌",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SeasonModeSelector(
                    current = seasonMode,
                    enabled = svc != null && !priceState.loading,
                    onSelect = { mode ->
                        val s = svc?.priceRepository() ?: return@SeasonModeSelector
                        scope.launch {
                            s.setSeasonMode(mode)
                            prefs.setSeasonModeId(mode.id)
                        }
                    },
                )
            }

            Button(
                onClick = {
                    val s = svc?.priceRepository() ?: return@Button
                    scope.launch { s.refreshLatest(forceRefresh = true) }
                },
                enabled = svc != null && !priceState.loading,
            ) { Text(if (priceState.loading) "불러오는 중..." else "새로고침") }

            LabelValue("현재 시즌", priceState.seasonId ?: "-")
            LabelValue("모드", priceState.mode.ifBlank { "-" })
            LabelValue("아이템 수", "${priceState.totalItems} (가격 있음 ${priceState.itemsWithPrice})")
            LabelValue(
                "마지막 갱신",
                if (priceState.lastUpdatedMs == 0L) "-"
                else formatUpdatedAgo(priceState.lastUpdatedMs),
            )
            priceState.lastError?.let { LabelValue("오류", it) }

            HorizontalDivider()
            ObservedPriceSection()

            if (priceState.priceById.isNotEmpty()) {
                HorizontalDivider()
                Text("샘플 가격 확인 (감지된 픽업 기준)", fontWeight = FontWeight.SemiBold)
                SamplePriceLookup(priceState)
            }
        }
    }
}

/**
 * 게임 내 경매장에서 직접 조회한 시세 목록.
 *
 * 조회 즉시 스냅샷 가격을 덮어쓰므로, 어떤 아이템이 덮였는지 여기서 확인할 수 있다.
 */
@Composable
private fun ObservedPriceSection() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val svc = service
    val observed by (svc?.observedPriceState
        ?: MutableStateFlow(emptyMap<String, com.mttd.data.prices.ObservedPriceStore.Observed>()))
        .collectAsStateWithLifecycle()

    // 서비스가 이미 들고 있는 것을 재사용. 여기서 새로 만들면 311 KB JSON 을 또 파싱한다.
    val itemInfo = svc?.itemInfoLookup()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("경매장 조회 반영 (${observed.size})", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        if (observed.isNotEmpty()) {
            OutlinedButton(onClick = { svc?.clearObservedPrices() }) { Text("비우기") }
        }
    }
    if (observed.isEmpty()) {
        Text(
            "게임 경매장에서 아이템 시세를 조회하면 그 값이 스냅샷보다 우선 적용됩니다. " +
                "가루(가루 거래)로 매겨진 호가는 제외합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (o in observed.values.sortedByDescending { it.observedAtMs }.take(8)) {
            val name = itemInfo?.lookup(o.itemId)?.name ?: o.itemId
            Text(
                "▸ $name · 평균 ${"%.4f".format(o.avgPrice)} " +
                    "(호가 ${o.samples}건, ${"%.4f".format(o.lowest)}~${"%.4f".format(o.highest)}) · " +
                    formatUpdatedAgo(o.observedAtMs),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 시세 출처 선택. 전환하면 이전 소스의 가격 맵을 버리고 즉시 재조회한다.
 *
 * 두 소스 모두 최초의 불꽃 결정 = 1 기준이라 스케일은 같고, 표본/갱신 주기만 다르다.
 */
@Composable
private fun PriceSourceSelector(
    current: com.mttd.data.prices.PriceSource,
    enabled: Boolean,
    onSelect: (com.mttd.data.prices.PriceSource) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (src in com.mttd.data.prices.PriceSource.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = current == src,
                        enabled = enabled,
                        onClick = { onSelect(src) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == src,
                    onClick = { onSelect(src) },
                    enabled = enabled,
                )
                Column {
                    Text(src.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        src.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 시즌 선택(정규/하드코어). 하드코어 캐릭터로 플레이 중이면 여기서 직접 "하드코어"로
 * 바꿔야 한다 — 항상 수동이다(자동 감지를 시도했었지만 신뢰성 문제로 걷어냄, [SeasonMode] 참고).
 */
@Composable
private fun SeasonModeSelector(
    current: com.mttd.data.prices.SeasonMode,
    enabled: Boolean,
    onSelect: (com.mttd.data.prices.SeasonMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (mode in com.mttd.data.prices.SeasonMode.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = current == mode,
                        enabled = enabled,
                        onClick = { onSelect(mode) },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                    enabled = enabled,
                )
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SamplePriceLookup(priceState: com.mttd.data.prices.PriceRepository.State) {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val session by (service?.sessionState ?: MutableStateFlow(SessionState()))
        .collectAsStateWithLifecycle()

    val pickups = session.recentPickups.take(5)
    if (pickups.isEmpty()) {
        Text("(세션에서 픽업 발생 시 여기 매칭 결과 표시)", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (p in pickups) {
            val id = p.itemId
            val price = id?.let { priceState.priceById[it] } ?: 0f
            val priceLabel = if (price <= 0f) "미상"
                             else if (price >= 1) "%,.0f".format(price)
                             else "%.4f".format(price)
            Text(
                "▸ ${p.itemName ?: "Unknown"} · $priceLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatUpdatedAgo(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val s = diff / 1000
    return when {
        s < 60 -> "${s}초 전"
        s < 3600 -> "${s / 60}분 전"
        else -> "${s / 3600}시간 ${(s % 3600) / 60}분 전"
    }
}

/**
 * 버전 표시 옆의 업데이트 재확인 버튼. 서비스가 시작 시 1 회 자동으로 확인하긴 하지만,
 * 앱을 오래 켜둔 채로 새 버전이 올라오면 재시작 전엔 알 방법이 없었다. 새 버전이 있으면
 * 이미 떠 있는 [UpdateBanner] 가 같은 상태를 구독하고 있어 알아서 나타난다.
 *
 * 아이콘 하나만 있을 땐 눈에 잘 안 띈다는 피드백을 받아 텍스트 라벨이 있는 버튼으로 바꿨다.
 */
@Composable
private fun UpdateCheckButton() {
    val context = LocalContext.current
    val app = context.applicationContext as TrackerApplication
    val service by app.trackerService.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = {
            val svc = service ?: return@OutlinedButton
            checking = true
            scope.launch {
                svc.checkForUpdate()
                checking = false
            }
        },
        enabled = service != null && !checking,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (checking) "확인 중..." else "업데이트 확인",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ContactCard() {
    val context = LocalContext.current
    fun openUrl(url: String) {
        val i = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("문의 / 후원", fontWeight = FontWeight.SemiBold)
            Text(
                "오픈카톡",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl("https://open.kakao.com/o/gKyqLeJi") },
            )
            Text(
                "Ko-fi 후원하기",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl("https://ko-fi.com/listil") },
            )
        }
    }
}

/**
 * 앱 종료. 최근 앱 목록에서 카드를 스와이프해도 종료되도록 서비스는
 * `stopWithTask=true` 로 설정했지만, 그 전에 명시적으로 끌 수 있는 버튼도 필요해서 추가.
 * 로그 추적·오버레이를 모두 정리하고 액티비티도 닫는다.
 */
@Composable
private fun ExitCard() {
    val context = LocalContext.current
    var confirmExit by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("앱 종료", fontWeight = FontWeight.SemiBold)
            Text(
                "로그 추적과 오버레이를 모두 중지하고 앱을 종료합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (confirmExit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmExit = false }) { Text("취소") }
                    TextButton(onClick = {
                        com.mttd.service.TrackerForegroundService.stop(context)
                        (context as? android.app.Activity)?.finishAndRemoveTask()
                    }) {
                        Text("종료 확인", color = Color(0xFFF87171))
                    }
                }
            } else {
                OutlinedButton(onClick = { confirmExit = true }) { Text("앱 종료") }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    if (ms <= 0) return "0초"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d시간 %02d분 %02d초".format(h, m, s)
        m > 0 -> "%d분 %02d초".format(m, s)
        else -> "%d초".format(s)
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
