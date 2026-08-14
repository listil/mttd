package com.mttd.data.update

import android.util.Log
import com.mttd.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * GitHub Releases 기반 업데이트 확인 — **알림만 하고 설치는 하지 않는다.**
 *
 * 자동 설치를 하려면 `REQUEST_INSTALL_PACKAGES` 를 받거나 Shizuku 로 `pm install` 을 돌려야 하는데,
 * 이 앱은 shell 권한 프로세스([com.mttd.service.UserService])를 **읽기 전용**으로 유지하는 게
 * 원칙이라 그 경로를 열지 않는다. 자동 업데이트가 필요하면 Obtainium 같은 외부 도구를 쓰면 된다.
 *
 * 비교는 `versionName` ↔ 릴리스 태그(`vX.Y.Z`) 의 semver 비교.
 */
class UpdateChecker {

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return 새 버전이 있으면 [Update], 최신이면 null.
     *         네트워크 실패 등은 null 로 삼킨다 (업데이트 확인 실패로 앱을 방해하지 않음).
     */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            val resp = client.newCall(req).awaitResponse()
            val body = resp.use { r ->
                // 404 = 릴리스가 아직 없음, 403 = rate limit. 둘 다 조용히 포기.
                if (!r.isSuccessful) {
                    Log.i(TAG, "check skipped: HTTP ${r.code}")
                    return@withContext null
                }
                r.body?.string() ?: return@withContext null
            }
            val rel = json.decodeFromString<GithubRelease>(body)
            if (rel.draft || rel.prerelease) return@withContext null

            val latest = rel.tagName.removePrefix("v").trim()
            val current = BuildConfig.VERSION_NAME.substringBefore("-").trim()
            if (compareSemver(latest, current) <= 0) return@withContext null

            // 릴리스 하나에 shizuku/direct 두 APK가 같이 붙으므로, 지금 실행 중인 flavor 이름이
            // 파일명에 포함된 걸 우선한다(둘 다 "mttd-X.Y.Z-<flavor>-release.apk" 로 산출됨) —
            // 안 그러면 direct 유저한테 shizuku APK를 업데이트로 안내하는 식의 오배정이 생긴다.
            val apks = rel.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val apk = apks.firstOrNull { it.name.contains(BuildConfig.FLAVOR, ignoreCase = true) }
                ?: apks.firstOrNull()
            Update(
                versionName = latest,
                releaseUrl = rel.htmlUrl,
                apkUrl = apk?.browserDownloadUrl,
                apkSizeBytes = apk?.size ?: 0,
                notes = rel.body.orEmpty().trim().take(600),
            ).also { Log.i(TAG, "update available: $current -> $latest") }
        } catch (t: Throwable) {
            Log.i(TAG, "check failed: ${t.message}")
            null
        }
    }

    data class Update(
        val versionName: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val apkSizeBytes: Long,
        val notes: String,
    )

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) { cont.resume(response) {} }
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

    companion object {
        private const val TAG = "mTTD.Update"

        /**
         * `1.2.10` vs `1.3.0` 같은 semver 비교. 숫자 파트만 본다.
         * @return a > b 면 양수, 같으면 0, a < b 면 음수.
         */
        fun compareSemver(a: String, b: String): Int {
            val pa = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
