package com.mttd.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.mttd.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 재현이 안 되는 원격 리포트(Shizuku 바인딩 실패 등)를 진단하기 위한 경량 이벤트 로거.
 *
 * logcat 은 유저 쪽에서 adb 없이 못 보므로, 앱이 자체적으로 상태 전이/예외를 파일에 남기고
 * "로그 보내기" 버튼으로 시스템 공유 시트(카카오톡 등)에 넘길 수 있게 한다. [CrashLogger] 와
 * 달리 크래시가 아니라도 계속 누적하는 게 목적이라 앱 전용 저장소(filesDir)에 쓰고,
 * 공유 시점에만 [FileProvider] 로 content:// URI 를 만들어 노출한다.
 *
 * 원본 게임 로그 내용은 절대 여기 들어가지 않는다 — 기록 대상은 이 앱의 내부 상태 전이뿐.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostic.log"
    private const val MAX_BYTES = 256 * 1024
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 확장 지점 — flavor별로 자기만의 추가 진단 정보를 붙이고 싶을 때 설정한다.
     * 예: `direct` flavor 디버그 빌드는 이미 쥐고 있는 adb 셸 연결로 실제 logcat 을 떠서
     * 여기 꽂는다 (`TrackerApplication.onCreate()` 에서 등록). `shizuku` flavor 는 셸 접근
     * 자체가 없어서 건드리지 않고 항상 null — [main] 이 어느 flavor인지 몰라도 되게 하는 용도.
     */
    var extraSection: (suspend () -> String?)? = null

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        try {
            val file = logFile(context)
            rotateIfNeeded(file)
            file.appendText("${timeFmt.format(Date())} [$tag] $message\n")
        } catch (_: Throwable) {
            // 로그 남기다가 죽으면 안 되니 조용히 무시.
        }
    }

    /** "로그 보내기" 버튼에서 호출 — 시스템 공유 시트(카카오톡 등)를 띄우는 인텐트를 만든다. */
    suspend fun buildShareIntent(context: Context): Intent {
        val exportDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        exportDir.listFiles()?.forEach { it.delete() }
        val exportFile = File(exportDir, "mttd-diagnostic-${System.currentTimeMillis()}.txt")
        val body = logFile(context).takeIf { it.exists() }?.readText().orEmpty()
        val extra = try { extraSection?.invoke() } catch (_: Throwable) { null }
        val content = buildString {
            append(header())
            append("---\n")
            append(body.ifBlank { "(기록된 이벤트 없음)\n" })
            if (!extra.isNullOrBlank()) {
                append("\n--- logcat ---\n")
                append(extra)
            }
        }
        exportFile.writeText(content)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "mTTD 진단 로그")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "진단 로그 보내기").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun header(): String = buildString {
        appendLine("mTTD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Exported: ${timeFmt.format(Date())}")
    }

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 이벤트 빈도가 낮아 자주 발동하진 않지만, 오래 켜둔 세션 대비 상한을 둔다. */
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val lines = file.readLines()
        val keep = lines.takeLast(lines.size / 2)
        file.writeText(keep.joinToString("\n", postfix = "\n"))
    }
}
