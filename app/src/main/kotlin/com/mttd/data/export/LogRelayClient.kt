package com.mttd.data.export

import com.mttd.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 미니 토치DB "로그 릴레이" API — `GetPlayerData` 시작 지점부터 파일 끝까지의 원본 로그 슬라이스
 * ([CharacterLoadoutTracker.lastSnapshotStartOffset] 부터 재읽기, 스펙 문서
 * https://mini-tlidb.winterer.workers.dev/logimport_spec 참조)를 그대로 올리고, 그 데이터를
 * 가리키는 1회용 URL을 받는다.
 *
 * 유일한 outbound endpoint:
 * - `POST https://mini-tlidb.winterer.workers.dev/api/logrelay?src=mttd` (body: 로그 슬라이스 원문,
 *   `text/plain`)
 *
 * 스펙이 요구하는 대로 커스텀 `User-Agent` 를 반드시 실어야 한다 — 기본 OkHttp UA(`okhttp/x.y.z`)는
 * Cloudflare 가 봇으로 보고 403 으로 막는다.
 *
 * 응답 `tok` 은 1회용 토큰이고 서버가 `expiresInSec`(2026-08-14 실측 600초 = 10분) 후 폐기한다 —
 * 안 열어보면 자동 소멸. 응답의 `url` 을 그대로 시스템 브라우저로 넘기면, 그 페이지의 JS가 토큰으로
 * 원본을 가져와 파싱한다(기존 "로그 가져오기" 파서 재사용 — 이 앱은 필드 하나도 직접 안 뽑는다).
 *
 * 이 클래스가 이 앱에서 mini-tlidb.winterer.workers.dev 로 직접 통신하는 유일한 지점이다
 * (그 외엔 [Mli1Codec] 처럼 URL만 만들어 브라우저에 넘기고 앱 프로세스는 안 건드림) — README/INSTALL
 * 의 네트워크 호스트 목록에 등재돼 있어야 한다.
 */
class LogRelayClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param rawSlice `GetPlayerData` 시작 마커부터 (재접속 등으로 갱신되지 않았다면) 현재 파일
     *   끝까지의 원본 로그 텍스트. [CharacterLoadoutTracker.lastSnapshotStartOffset] 기준으로
     *   [com.mttd.data.log.LogPoller.readRange] 로 다시 읽어온 값을 그대로 넘기면 된다 — 로그인
     *   시점의 `GetPlayerData` 블록뿐 아니라 그 뒤에 이어지는 `ItemChange@` 델타(스킬 교체 등)까지
     *   포함해야 스펙과 맞는다.
     */
    suspend fun relay(rawSlice: String): LogRelayResult = withContext(Dispatchers.IO) {
        val body = rawSlice.toRequestBody(TEXT_PLAIN)
        val req = Request.Builder()
            .url("$BASE_URL/api/logrelay?src=mttd")
            .header("User-Agent", "mttd/${BuildConfig.VERSION_NAME}")
            .post(body)
            .build()
        val resp = client.newCall(req).awaitResponse()
        resp.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for logrelay")
            val text = r.body?.string() ?: throw IOException("empty logrelay body")
            json.decodeFromString<LogRelayResult>(text)
        }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) { cont.resume(response) {} }
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            })
            cont.invokeOnCancellation {
                try { cancel() } catch (_: Throwable) {}
            }
        }

    companion object {
        const val BASE_URL = "https://mini-tlidb.winterer.workers.dev"
        private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}

@Serializable
data class LogRelayResult(
    val tok: String,
    val url: String,
    val expiresInSec: Int,
)
