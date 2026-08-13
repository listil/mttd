package com.mttd.data.export

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * mini-tlidb "MLI1" 코드 인코더/디코더.
 *
 * 스펙(https://mini-tlidb.winterer.workers.dev/logimport_spec):
 *   코드 = "MLI1." + base64url(rawDeflate(UTF-8 JSON))
 *   - rawDeflate: RFC 1951, zlib 헤더/트레일러 없음 (`Deflater(nowrap = true)`)
 *   - base64url: RFC 4648 URL-safe, `=` 패딩 제거
 *
 * 압축 레벨/전략은 표준을 벗어나지 않는 한 결과 코드 바이트가 스펙 예시와 달라도 무방하다
 * (수신측은 inflate 만 하므로 압축기 구현이 달라도 상호운용됨) — [Mli1CodecTest] 가
 * 검증하는 것도 "우리가 만든 코드를 디코드하면 원본 JSON이 나온다" +
 * "스펙 공식 예시 코드를 디코드하면 스펙 JSON이 나온다"이지, 코드 바이트 자체의 일치가 아니다.
 */
object Mli1Codec {

    const val PREFIX = "MLI1."
    const val DEFAULT_ENDPOINT = "https://mini-tlidb.winterer.workers.dev/craft_search"

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(loadout: CharacterLoadout): String {
        val jsonText = json.encodeToString(loadout)
        val compressed = deflateRaw(jsonText.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    fun buildUrl(loadout: CharacterLoadout, endpoint: String = DEFAULT_ENDPOINT): String =
        "$endpoint?logimport=${encode(loadout)}"

    /** 코드를 원본 JSON 문자열로 복원. 주로 [encode]/[buildUrl] 검증용. */
    fun decode(code: String): String {
        require(code.startsWith(PREFIX)) { "unsupported code prefix: $code" }
        val b64 = code.removePrefix(PREFIX)
        val compressed = Base64.getUrlDecoder().decode(padBase64Url(b64))
        return String(inflateRaw(compressed), Charsets.UTF_8)
    }

    private fun padBase64Url(s: String): String {
        val remainder = s.length % 4
        return if (remainder == 0) s else s + "=".repeat(4 - remainder)
    }

    private fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 3 + 64)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
