package org.alpha.hub

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class UpdateChecker(private val ctx: Context) {
    @Volatile private var remote = 0L
    @Volatile private var last = 0L

    private fun local(): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (_: Exception) { 0L }

    fun check() {
        val now = System.currentTimeMillis()
        if (remote > 0 && now - last < 6L * 3600L * 1000L) return
        try {
            val c = URL(VERSION_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "AlphaAI/0.1")
            if (c.responseCode == 200) {
                val txt = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                remote = JSONObject(txt).optLong("version_code", 0L)
                last = now
            }
            c.disconnect()
        } catch (_: Exception) { }
    }

    fun available(): Boolean = remote > local()

    companion object {
        const val VERSION_URL = "https://github.com/Kinfemichael-Alpha/alpha-assets/releases/download/latest/version.json"
        const val APK_URL = "https://github.com/Kinfemichael-Alpha/alpha-assets/releases/download/latest/alpha-ai.apk"
    }
}
