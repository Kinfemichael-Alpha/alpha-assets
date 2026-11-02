package org.alpha.hub

import android.util.Base64
import java.security.MessageDigest

object UrlCodec {
    private const val PREFIX = "AL1"

    private fun key(): ByteArray = MessageDigest.getInstance("SHA-256").digest("alpha-ai|url|v1|k7Qm".toByteArray(Charsets.UTF_8))

    private fun mix(data: ByteArray): ByteArray {
        val k = key()
        return ByteArray(data.size) { i -> (data[i].toInt() xor k[i % k.size].toInt() xor ((i * 31 + 7) and 0xFF)).toByte() }
    }

    private fun sum(data: ByteArray): Int {
        var s = 0x5A5A
        for (b in data) s = (s * 31 + (b.toInt() and 0xFF)) and 0xFFFF
        return s
    }

    fun encode(url: String): String {
        val p = url.toByteArray(Charsets.UTF_8)
        val s = sum(p)
        val body = mix(p + byteArrayOf((s shr 8).toByte(), s.toByte()))
        return PREFIX + Base64.encodeToString(body, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun decode(input: String): String? {
        val clean = input.filter { !it.isWhitespace() }
        if (clean.startsWith("https://")) return clean
        if (!clean.startsWith(PREFIX)) return null
        return try {
            val raw = mix(Base64.decode(clean.substring(PREFIX.length), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            if (raw.size < 10) return null
            val p = raw.copyOfRange(0, raw.size - 2)
            val s = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
            if (sum(p) != s) return null
            val url = String(p, Charsets.UTF_8)
            if (url.startsWith("https://")) url else null
        } catch (_: Exception) { null }
    }
}
