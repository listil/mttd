package com.mttd.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mttd.BuildConfig
import com.mttd.IUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 원본 게임 로그(`UE_game.log`) 의 최근 구간을 zip 으로 묶어 시스템 공유 시트(카카오톡 등)로 넘긴다.
 *
 * [DiagnosticLog] 는 이 앱의 상태 전이만 담고 게임 로그는 일부러 빼는데, 파싱 버그(시즌 변경으로
 * 로그 형식이 바뀐 경우 등)는 실제 게임 로그 없이는 재현이 안 돼서 유저가 직접 보낼 수 있게 한
 * 별도 경로다. 읽기는 기존 읽기 전용 AIDL([IUserService.readFileChunk]) 만 쓰고, 전송은 유저가
 * 고른 앱으로 공유 시트를 통해서만 나간다 — 이 앱이 직접 어딘가로 올리지 않는다.
 *
 * 게임 로그엔 캐릭터명·계정 정보(`GetPlayerData` 응답 등) 가 들어 있으므로, 호출측 UI 가 보내기
 * 전에 반드시 안내를 띄운다.
 */
object GameLogExport {
    /** 서버 쪽 MAX_CHUNK(262144) 와 같게 — 더 크게 요청해도 잘려 온다. */
    private const val CHUNK = 256 * 1024
    private const val EXPORT_DIR = "diagnostics"
    private const val FILE_PREFIX = "mttd-gamelog-"
    private val timeFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * @param maxBytes 파일 끝에서부터 담을 최대 크기. null 이면 파일 전체.
     * @param onProgress 0f..1f 진행률 (IO 스레드에서 호출됨).
     * @throws IllegalStateException 로그 파일을 못 읽었을 때 (메시지는 그대로 유저에게 보여줄 문구).
     */
    suspend fun buildShareIntent(
        context: Context,
        service: IUserService,
        logPath: String,
        maxBytes: Long?,
        onProgress: (Float) -> Unit = {},
    ): Intent = withContext(Dispatchers.IO) {
        val size = service.getFileSize(logPath)
        check(size > 0) { "게임 로그 파일을 찾지 못했습니다." }

        // 읽는 동안에도 게임이 계속 쓰므로 시작 시점 크기까지만 담는다.
        val requestedStart = if (maxBytes == null) 0L else (size - maxBytes).coerceAtLeast(0L)

        val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        exportDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }?.forEach { it.delete() }
        val stamp = timeFmt.format(Date())
        val zipFile = File(exportDir, "$FILE_PREFIX$stamp.zip")

        var startOffset = requestedStart
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("UE_game.log"))
            var offset = requestedStart
            var first = requestedStart > 0
            while (offset < size) {
                val want = minOf(CHUNK.toLong(), size - offset).toInt()
                val chunk = service.readFileChunk(logPath, offset, want) ?: ByteArray(0)
                if (chunk.isEmpty()) break
                offset += chunk.size
                if (first) {
                    // 중간에서 잘라 읽기 시작했으면 깨진 첫 줄은 버리고 다음 줄부터 담는다.
                    first = false
                    val nl = chunk.indexOf('\n'.code.toByte())
                    if (nl >= 0) {
                        startOffset = offset - chunk.size + nl + 1
                        zip.write(chunk, nl + 1, chunk.size - nl - 1)
                    }
                } else {
                    zip.write(chunk)
                }
                onProgress(((offset - requestedStart).toFloat() / (size - requestedStart)).coerceIn(0f, 1f))
            }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("mttd-info.txt"))
            zip.write(
                buildString {
                    appendLine("mTTD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), flavor=${BuildConfig.FLAVOR}")
                    appendLine("Exported: $stamp")
                    appendLine("Path: $logPath")
                    appendLine("File size at export: $size bytes")
                    appendLine("Included range: $startOffset..$size (${size - startOffset} bytes)")
                }.toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "mTTD 게임 로그")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "게임 로그 보내기").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
