package com.mttd.data.prices

import com.mttd.proto.price.PriceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * 시세 데이터 API.
 *
 * 유일한 outbound endpoint (보안 원칙 3):
 * - `GET https://etor-zero.981001.xyz/etor-api/api/prices-snapshot/{seasonId}?format=protobuf`
 *
 * POST, 크라우드 기여, 리포트 업로드 등 **다른 아무 endpoint 도 호출하지 않는다**.
 *
 * WAF 는 Phase 0 실증으로 이 API 서버에 없음 확인됨 → 헤더 없이도 200. 커스텀 헤더 안 붙임.
 */
class PriceApi {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 시즌별 현재 시세 스냅샷 요청.
     *
     * @param seasonId  게임 시즌 ID (예: "1501" for S13, 하드코어는 `+30` 오프셋인 "1531" —
     *                  [com.mttd.data.prices.PriceRepository] 참고). 유저가 서버로 정보를
     *                  넘기는 유일한 지점.
     */
    suspend fun fetchSnapshot(seasonId: String): PriceResponse = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/etor-api/api/prices-snapshot/$seasonId?format=protobuf"
        val req = Request.Builder().url(url).get().build()
        val resp = client.newCall(req).awaitResponse()
        resp.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val bytes = r.body?.bytes() ?: throw IOException("empty body")
            PriceResponse.parseFrom(bytes)
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
        const val BASE_URL = "https://etor-zero.981001.xyz"
    }
}
