package com.mttd.diagnostics

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mttd.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 크래시 시 스택트레이스를 공용 다운로드 폴더(`Download/mTTD/`)에 텍스트 파일로 남긴다.
 *
 * 자동으로 어디로도 전송하지 않는다 — 이 앱의 "아웃바운드는 정해진 호스트 몇 개뿐" 원칙과
 * 상충하지 않도록, Crashlytics 류의 자동 업로드 SDK 를 쓰지 않는다.
 *
 * 앱 내부 저장소(filesDir) 대신 공용 다운로드 폴더를 쓰는 이유: 내부 저장소는 앱이 정상적으로
 * 열려야만(공유 버튼 등으로) 꺼낼 수 있는데, **정작 크래시 직후 앱이 계속 죽는 상황이면 그 화면
 * 자체에 못 들어간다.** 다운로드 폴더는 앱이 아예 안 켜져도 아무 파일관리자로 바로 열 수 있고,
 * `MediaStore.Downloads` 는 API 29+ 에서 별도 권한 선언 없이(자기 앱이 만든 파일 한정) 쓸 수 있다.
 */
object CrashLogger {
    private const val SUBDIR = "mTTD"
    private const val RELATIVE_PATH = "Download/$SUBDIR/"
    private const val MAX_LOGS = 5

    /** [android.app.Application.onCreate] 맨 앞에서 한 번 호출. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (_: Throwable) {
                // 로그를 남기다가 또 죽으면 안 되니 여기서는 조용히 무시.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val header = buildString {
            appendLine("mTTD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), flavor=${BuildConfig.FLAVOR}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("Time: $timestamp")
            appendLine("---")
        }
        val content = header + throwable.stackTraceToString()

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "crash-$timestamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }

        trimOldLogs(context)
    }

    /** 오래된 것부터 정리, 최근 [MAX_LOGS] 개만 유지 (무한정 쌓이지 않게). */
    private fun trimOldLogs(context: Context) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(RELATIVE_PATH, "crash-%")
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            var seen = 0
            while (cursor.moveToNext()) {
                seen++
                if (seen > MAX_LOGS) {
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                    resolver.delete(uri, null, null)
                }
            }
        }
    }
}
