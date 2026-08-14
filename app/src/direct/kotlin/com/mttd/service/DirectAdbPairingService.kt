package com.mttd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.Observer
import com.mttd.TrackerApplication
import com.mttd.data.adb.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 무선 디버깅 페어링 코드를 **화면 전환 없이** 알림 서랍에서 받기 위한 서비스.
 *
 * 왜 필요한가: 설정 앱의 "페어링 코드로 기기 페어링" 화면은 그 화면이 보이는 동안만 페어링
 * 세션을 유지한다 — mTTD로 전환해서 코드를 입력하려는 순간 세션이 끊긴다. Shizuku도 같은
 * 문제를 겪어서, mDNS로 페어링 서비스 포트를 자동 탐지한 뒤 RemoteInput 알림으로 코드만
 * 받는 방식을 쓴다(`AdbPairingService.kt`) — 이 클래스는 그 구조를 그대로 따른다.
 */
@RequiresApi(Build.VERSION_CODES.R)
class DirectAdbPairingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mdns: AdbMdns? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat(searchingNotification())
                startSearch()
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port > 0 && code.isNotBlank()) {
                    startForegroundCompat(workingNotification())
                    val manager = TrackerApplication.instance.accessManager
                    serviceScope.launch {
                        val result = manager.completePairing(port, code)
                        result.onSuccess {
                            showResultNotification(success = true, message = "mTTD가 연결됐습니다")
                        }.onFailure {
                            showResultNotification(success = false, message = it.message ?: it.javaClass.simpleName)
                        }
                        stopSearch()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } catch (_: Throwable) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
        }
    }

    private fun startSearch() {
        if (mdns != null) return
        val observer = Observer<Int> { port ->
            if (port > 0) {
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, foundNotification(port))
            }
        }
        mdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).also { it.start() }
    }

    private fun stopSearch() {
        mdns?.stop()
        mdns = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "mTTD 무선 디버깅 페어링", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) }
            )
        }
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, REQUEST_STOP,
        Intent(this, DirectAdbPairingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun replyPendingIntent(port: Int): PendingIntent = PendingIntent.getService(
        this, REQUEST_REPLY,
        Intent(this, DirectAdbPairingService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun searchingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("무선 디버깅 페어링 서비스 찾는 중...")
            .setContentText("설정 → 개발자 옵션 → 무선 디버깅 → \"페어링 코드로 기기 페어링\"을 열어주세요")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .addAction(NotificationCompat.Action.Builder(0, "중지", stopPendingIntent()).build())
            .build()

    private fun foundNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("6자리 페어링 코드").build()
        val replyAction = NotificationCompat.Action.Builder(0, "코드 입력", replyPendingIntent(port))
            .addRemoteInput(remoteInput)
            .build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("페어링 서비스를 찾았습니다")
            .setContentText("이 알림을 펼쳐서, 화면에 뜬 6자리 코드를 입력하세요")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .addAction(replyAction)
            .build()
    }

    private fun workingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("페어링 중...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private fun showResultNotification(success: Boolean, message: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(if (success) "페어링 성공" else "페어링 실패")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "direct_adb_pairing"
        private const val NOTIF_ID = 43
        private const val ACTION_START = "com.mttd.action.START_ADB_PAIRING"
        private const val ACTION_REPLY = "com.mttd.action.REPLY_ADB_PAIRING"
        private const val ACTION_STOP = "com.mttd.action.STOP_ADB_PAIRING"
        private const val EXTRA_PORT = "port"
        private const val KEY_CODE = "code"
        private const val REQUEST_REPLY = 1
        private const val REQUEST_STOP = 2

        fun startIntent(context: Context): Intent =
            Intent(context, DirectAdbPairingService::class.java).setAction(ACTION_START)
    }
}
