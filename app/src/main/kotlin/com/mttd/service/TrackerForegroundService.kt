package com.mttd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.mttd.MainActivity
import com.mttd.R
import com.mttd.TrackerApplication
import com.mttd.data.items.ItemInfoLookup
import com.mttd.data.log.LogLineFilter
import com.mttd.data.log.LogPoller
import com.mttd.data.log.MessageAssembler
import com.mttd.data.log.OffsetStore
import com.mttd.data.prefs.OverlayPrefs
import com.mttd.data.prices.PriceApi
import com.mttd.data.prices.PriceRepository
import com.mttd.diagnostics.DiagnosticLog
import com.mttd.domain.SessionAggregator
import com.mttd.domain.ValueCalculator
import com.mttd.domain.models.SessionState
import com.mttd.ui.overlay.OverlayHost
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 포그라운드 서비스 — 로그 폴러 호스트.
 *
 * 앱 UI 는 [LocalBinder] 로 바인딩해서 [status]/[lines] 를 관찰.
 */
class TrackerForegroundService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var poller: LogPoller? = null
    private var pollerBootstrap: kotlinx.coroutines.Job? = null
    private lateinit var offsetStore: OffsetStore
    private lateinit var itemInfo: ItemInfoLookup
    private lateinit var aggregator: SessionAggregator
    private lateinit var overlayPrefs: OverlayPrefs
    private lateinit var priceRepo: PriceRepository
    private lateinit var observedPrices: com.mttd.data.prices.ObservedPriceStore
    private lateinit var runRepo: com.mttd.data.runs.RunRepository
    private var overlay: OverlayHost? = null
    private val assembler = MessageAssembler()
    private val characterLoadoutTracker = com.mttd.domain.CharacterLoadoutTracker()

    /**
     * 마지막으로 관측된 캐릭터 장비/스킬/석판 스냅샷. 로그인 직후 한 번 오는 `GetPlayerData`
     * 전체 동기화를 파싱한 결과라, 앱을 켠 뒤 게임에 재접속(또는 로그인)해야 채워진다 —
     * [com.mttd.domain.CharacterLoadoutTracker] 클래스 주석 참조.
     */
    val loadoutState: StateFlow<com.mttd.data.export.CharacterLoadout?> get() = characterLoadoutTracker.loadout
    fun currentLoadout(): com.mttd.data.export.CharacterLoadout? = characterLoadoutTracker.latestLoadout
    fun currentLoadoutSyncedAtMs(): Long = characterLoadoutTracker.latestSyncAtMs

    /**
     * 실제 내보내기(로그 릴레이)에 쓸 원문 슬라이스 — `GetPlayerData` 시작 지점부터 지금 파일
     * 끝까지를 [LogPoller.readRange] 로 다시 읽어온다. [CharacterLoadoutTracker] 클래스 doc의
     * "원문 재전송" 섹션 참조. 아직 스냅샷을 못 봤거나(오프셋 없음), 폴러가 안 돌고 있거나, 그
     * 시점 이후 파일이 안 자랐으면 null.
     */
    suspend fun currentLoadoutExportBlock(): String? {
        val startOffset = characterLoadoutTracker.lastSnapshotStartOffset ?: return null
        val bytes = poller?.readRange(startOffset) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    val priceState get() = priceRepo.state
    fun priceRepository(): PriceRepository = priceRepo
    /** 경매장에서 직접 조회한 시세 (스냅샷보다 우선). */
    val observedPriceState get() = observedPrices.prices
    fun clearObservedPrices() = observedPrices.clear()
    /** 아이템 이름 조회. UI 가 따로 만들면 311 KB JSON 을 한 번 더 파싱하게 되므로 이걸 쓴다. */
    fun itemInfoLookup(): ItemInfoLookup = itemInfo

    /** 사용 가능한 새 버전. null 이면 최신이거나 아직 확인 전. */
    private val _availableUpdate =
        MutableStateFlow<com.mttd.data.update.UpdateChecker.Update?>(null)
    val availableUpdate: StateFlow<com.mttd.data.update.UpdateChecker.Update?> =
        _availableUpdate.asStateFlow()

    /**
     * 업데이트 확인. 서비스 시작 시 [checkForUpdateOnce] 로 1 회 자동 호출되고, 설정 탭의
     * "업데이트 확인" 버튼으로 수동으로도 부를 수 있다 (그래서 public + suspend).
     *
     * 알림만 하고 설치는 하지 않는다. 자동 설치를 하려면 `REQUEST_INSTALL_PACKAGES` 를 받거나
     * Shizuku 로 `pm install` 을 돌려야 하는데, [UserService] 를 읽기 전용으로 유지하는 게
     * 이 앱의 원칙이라 그 경로는 열지 않는다.
     */
    suspend fun checkForUpdate() {
        _availableUpdate.value = com.mttd.data.update.UpdateChecker().check()
    }

    private fun checkForUpdateOnce() {
        lifecycleScope.launch { checkForUpdate() }
    }


    private val _status = MutableStateFlow(LogPoller.PollingStatus())
    val status: StateFlow<LogPoller.PollingStatus> = _status.asStateFlow()

    val sessionState: StateFlow<SessionState>
        get() = aggregator.state

    // 디버그 화면 전용. 구독자가 없으면 아예 채우지 않으므로 버퍼를 크게 잡을 이유가 없다.
    private val _lines = MutableSharedFlow<String>(
        replay = 20,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    fun resetSession() {
        aggregator.resetSession()
        lifecycleScope.launch { runRepo.clear() }
    }
    fun togglePause() = aggregator.togglePause()

    /** "가치" 탭/거래소 HUD 의 수동 새로고침 버튼에서 호출. */
    fun refreshHoldings() = aggregator.refreshHoldings()

    /**
     * 잘못 집계된 회차 삭제. 세션 총합·아이템 합계도 남은 회차 기준으로 재계산된다.
     * 완료된 회차의 아이템 목록은 디스크에 있으므로 먼저 읽어와서 차감분을 넘긴다.
     */
    fun deleteRun(runId: Long) {
        lifecycleScope.launch {
            val items = runRepo.loadItems(runId)
            aggregator.deleteRun(runId, items)
            runRepo.delete(runId)
        }
    }

    /** 상세 팝업용 — 해당 회차의 아이템 목록을 디스크에서 읽는다. */
    suspend fun loadRunItems(runId: Long): List<com.mttd.domain.models.PickupSummary> {
        // 진행 중인 회차는 아직 디스크에 없으므로 메모리에서.
        aggregator.state.value.runs.firstOrNull { it.id == runId }
            ?.takeIf { it.inProgress }?.let { return it.items }
        return runRepo.loadItems(runId)
    }

    inner class LocalBinder : Binder() {
        val service: TrackerForegroundService get() = this@TrackerForegroundService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.log(applicationContext, "Service", "onCreate")
        savedStateRegistryController.performRestore(null)
        offsetStore = OffsetStore(applicationContext)
        itemInfo = ItemInfoLookup(applicationContext)
        overlayPrefs = OverlayPrefs(applicationContext)
        priceRepo = PriceRepository(PriceApi(), onTtdItemsFetched = { items ->
            val extra = items.mapValues { (_, item) ->
                ItemInfoLookup.ItemInfo(name = item.name, type = item.type)
            }
            itemInfo.mergeGapFill(extra)
        })
        observedPrices = com.mttd.data.prices.ObservedPriceStore()
        runRepo = com.mttd.data.runs.RunRepository(applicationContext, itemInfo)
        aggregator = SessionAggregator(
            itemInfo = itemInfo,
            valueCalculator = ValueCalculator(priceRepo, observedPrices),
            observedPrices = observedPrices,
            mapNames = com.mttd.data.maps.MapNameLookup(applicationContext),
            // 완료된 회차의 아이템 목록은 디스크로. 메모리엔 요약만 남는다.
            onRunFinished = { run -> lifecycleScope.launch { runRepo.save(run) } },
        )
        ensureNotificationChannel()
        TrackerApplication.instance.setTrackerService(this)
        startPriceRefreshLoop()
        checkForUpdateOnce()
        restoreRunsFromDisk()
    }

    /**
     * 프로세스가 죽었다 살아난 경우(START_STICKY 재시작, 앱 업데이트 등) 회차를 되살린다.
     *
     * 아이템 목록은 평소 메모리에 없으므로, 세션 전체 아이템 합계는 여기서 **한 번만**
     * 디스크에서 읽어 재구성한다.
     */
    private fun restoreRunsFromDisk() {
        lifecycleScope.launch {
            val summaries = runRepo.loadSummaries()
            if (summaries.isEmpty()) return@launch
            val merged = runRepo.loadMergedItems()
            aggregator.restoreRuns(summaries, merged)
            Log.i(TAG, "restored ${summaries.size} runs from disk (${merged.size} item types)")
        }
    }

    /**
     * 시세를 서비스가 직접 가져온다.
     *
     * 예전엔 [com.mttd.ui.onboarding.OnboardingScreen] 의 LaunchedEffect 에서만 fetch 해서,
     * 앱 UI 를 한 번도 안 열면 가격 맵이 비어 있고 모든 픽업이 0 으로 계산됐다.
     * (오버레이만 띄우고 게임을 하면 수익이 계속 0)
     */
    private fun startPriceRefreshLoop() {
        lifecycleScope.launch {
            // 저장된 시세 출처 복원 (첫 fetch 전에)
            runCatching {
                priceRepo.restoreSource(
                    com.mttd.data.prices.PriceSource.fromId(overlayPrefs.priceSourceId.first())
                )
            }
            while (true) {
                val r = priceRepo.refreshLatest()
                val ok = r.isSuccess && priceRepo.state.value.itemsWithPrice > 1
                // 성공하면 TTL(1h) 주기로 재확인, 실패하면 1분 뒤 재시도.
                kotlinx.coroutines.delay(if (ok) PRICE_REFRESH_INTERVAL_MS else PRICE_RETRY_INTERVAL_MS)
            }
        }
    }

    fun ensureOverlay(): OverlayHost {
        if (overlay == null) {
            overlay = OverlayHost(
                context = this,
                lifecycleOwner = this,
                savedStateOwner = this,
                sessionState = aggregator.state,
                priceState = priceRepo.state,
                prefs = overlayPrefs,
            ).also { it.attach() }
        }
        return overlay!!
    }

    fun destroyOverlay() {
        overlay?.detach()
        overlay = null
    }

    fun overlayHost(): OverlayHost? = overlay

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForegroundOnce()
                val path = intent.getStringExtra(EXTRA_LOG_PATH)
                if (path != null) startPoller(path) else ensurePollerRunning()
            }
            ACTION_STOP -> {
                stopPoller()
                destroyOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                startForegroundOnce()   // Overlay 만 켜는 경우도 서비스는 foreground 유지
                ensureOverlay()
                ensurePollerRunning()
            }
            ACTION_HIDE_OVERLAY -> {
                destroyOverlay()
            }
            ACTION_SHOW_HUD -> {
                ensureOverlay().showHud()
                ensurePollerRunning()
            }
            ACTION_HIDE_HUD -> {
                overlay?.hideHud()
            }
            // intent == null: START_STICKY 로 시스템이 서비스를 되살린 경우.
            // 예전엔 어느 분기에도 안 걸려서 foreground 승격도, 폴러 시작도 안 됐다.
            else -> {
                startForegroundOnce()
                ensurePollerRunning()
            }
        }
        return START_STICKY
    }

    /**
     * 폴러가 안 돌고 있으면 서비스가 **스스로** 게임 패키지를 찾아 폴링을 시작한다.
     *
     * 예전엔 폴러 시작 경로가 `MainActivity.onResume()` 하나뿐이라,
     * 오버레이만 띄우거나 시스템이 서비스를 재시작한 상태에서는 로그를 아예 안 읽었다.
     * → "가방 정렬하고 앱을 다녀와야 동작" 의 직접 원인.
     *
     * 특권 접근 계층(Shizuku/direct-adb)이 아직 준비 안 됐으면 준비될 때까지 재시도한다.
     */
    @Synchronized
    fun ensurePollerRunning() {
        if (poller != null || pollerBootstrap?.isActive == true) return
        pollerBootstrap = lifecycleScope.launch {
            val access = TrackerApplication.instance.accessManager
            while (poller == null) {
                if (!access.ready.value) {
                    // 권한 요청/페어링 UI 는 띄우지 않는다 (3 초마다 반복될 수 있으므로).
                    // 그건 앱 UI 에서만 하고, 여기서는 조용한 재연결만 재시도.
                    access.retryConnect()
                    kotlinx.coroutines.delay(POLLER_BOOTSTRAP_RETRY_MS)
                    continue
                }
                val path = resolveLogPath(access.service)
                if (path == null) {
                    kotlinx.coroutines.delay(POLLER_BOOTSTRAP_RETRY_MS)
                    continue
                }
                startPoller(path)
            }
        }
    }

    private suspend fun resolveLogPath(userService: com.mttd.IUserService?): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val svc = userService ?: return@withContext null
            val pkg = try {
                svc.listInstalledGamePackages().lines().firstOrNull { it.isNotBlank() }
            } catch (t: Throwable) {
                Log.w(TAG, "listInstalledGamePackages failed", t); null
            } ?: return@withContext null
            "/sdcard/Android/data/$pkg/files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"
        }

    private fun startPoller(path: String) {
        stopPoller()
        val access = TrackerApplication.instance.accessManager
        val p = LogPoller(
            service = { access.service },
            offsetStore = offsetStore,
            logPath = path,
        )
        poller = p
        // status/lines forwarding
        lifecycleScope.launch { p.status.collect { _status.value = it } }
        lifecycleScope.launch {
            p.lines.collect { line ->
                // 디버그용 라인 스트림은 **보는 사람이 있을 때만** 채운다.
                // replay 20 + 버퍼 1024 라 무조건 흘리면 평균 135 자 × 1044 줄이
                // 항상 힙에 남고, 초당 100 줄이 들어오는 동안 계속 할당/방출이 일어난다.
                if (_lines.subscriptionCount.value > 0) _lines.tryEmit(line.text)
                // 라인 단위 관측 (맵 코드네임 등 assembler 밖 컨텍스트)
                aggregator.observeLine(line.text)
                characterLoadoutTracker.observeLine(line.text, line.offset)
                // 필터 통과분만 assembler 에 공급
                if (!LogLineFilter.shouldExclude(line.text)) {
                    assembler.feed(line.text)?.let { msg -> aggregator.consume(msg) }
                }
            }
        }
        p.start()
        Log.i(TAG, "poller started: $path")
        // 준비가 다 끝났으면(오버레이 권한 있음) 굳이 버튼을 누르게 하지 않고 바로 띄운다.
        autoShowOverlay()
    }

    /**
     * Shizuku·폴러·오버레이 권한이 모두 갖춰졌으면 오버레이를 자동으로 표시.
     *
     * 사용자가 명시적으로 숨긴 상태(HUD 접기와 별개로 오버레이 자체를 끈 경우)는 존중해야 하므로
     * 이미 붙어 있으면 아무것도 하지 않는다 ([ensureOverlay] 가 멱등).
     */
    private fun autoShowOverlay() {
        if (overlay != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        ensureOverlay()
        Log.i(TAG, "overlay auto-shown")
    }

    private fun stopPoller() {
        pollerBootstrap?.cancel()
        pollerBootstrap = null
        poller?.stop()
        poller = null
    }

    override fun onDestroy() {
        DiagnosticLog.log(applicationContext, "Service", "onDestroy")
        stopPoller()
        destroyOverlay()
        TrackerApplication.instance.setTrackerService(null)
        super.onDestroy()
    }

    private fun startForegroundOnce() {
        val notif = buildNotification("로그 감시 중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "mTTD 로그 감시",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "게임 로그 파일을 폴링하는 백그라운드 서비스" }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "mTTD.Service"
        private const val CHANNEL_ID = "tli_log_watch"
        private const val NOTIF_ID = 42
        /** 시세 TTL 과 동일 (PriceRepository.TTL_MS = 1h). */
        private const val PRICE_REFRESH_INTERVAL_MS = 60L * 60 * 1000
        private const val PRICE_RETRY_INTERVAL_MS = 60L * 1000
        /** Shizuku 준비 / 게임 패키지 탐색 재시도 간격. */
        private const val POLLER_BOOTSTRAP_RETRY_MS = 3_000L
        const val ACTION_START = "com.mttd.action.START_LOG"
        const val ACTION_STOP = "com.mttd.action.STOP_LOG"
        const val ACTION_SHOW_OVERLAY = "com.mttd.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.mttd.action.HIDE_OVERLAY"
        const val ACTION_SHOW_HUD = "com.mttd.action.SHOW_HUD"
        const val ACTION_HIDE_HUD = "com.mttd.action.HIDE_HUD"
        const val EXTRA_LOG_PATH = "log_path"

        fun start(context: Context, logPath: String) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LOG_PATH, logPath)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        /**
         * 로그 경로를 모르는 상태에서 서비스만 띄운다.
         * 서비스가 Shizuku 준비를 기다렸다가 스스로 게임 패키지를 찾아 폴링을 시작한다.
         */
        fun startSelfManaged(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }

        fun showOverlay(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun hideOverlay(context: Context) {
            val i = Intent(context, TrackerForegroundService::class.java).apply {
                action = ACTION_HIDE_OVERLAY
            }
            context.startService(i)
        }
    }
}
