package org.alpha.hub

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ConfigSync(private val ctx: Context) {
    private val base = "https://huggingface.co/datasets/Kinfemichael/Alpha-links/raw/main/"
    private val files = listOf("contacts.json", "books.json", "exams.json")
    private val dir get() = File(ctx.filesDir, "config").apply { mkdirs() }
    private val meta get() = File(dir, "meta.json")

    private fun readMeta(): JSONObject = try { JSONObject(meta.readText(Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }

    fun cached(name: String): String? = try { File(dir, name).takeIf { it.exists() }?.readText(Charsets.UTF_8) } catch (_: Exception) { null }

    fun due(): Boolean = System.currentTimeMillis() - readMeta().optLong("last", 0L) > 24L * 3600L * 1000L

    @Synchronized
    fun refresh(validate: (String, String) -> Boolean): JSONObject {
        val m = readMeta()
        val etags = m.optJSONObject("etags") ?: JSONObject()
        var changed = 0; var failed = 0
        for (f in files) {
            try {
                val c = URL(base + f).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "AlphaAI/0.1")
                val have = File(dir, f).exists()
                if (have && etags.optString(f).isNotEmpty()) c.setRequestProperty("If-None-Match", etags.optString(f))
                val code = c.responseCode
                if (code == 304) { c.disconnect(); continue }
                if (code != 200) { c.disconnect(); if (code != 404) failed++; continue }
                val bytes = c.inputStream.use { it.readBytes() }
                val tag = c.getHeaderField("ETag") ?: ""
                c.disconnect()
                if (bytes.size > 4 * 1024 * 1024) continue
                val text = String(bytes, Charsets.UTF_8)
                JSONObject(text)
                if (!validate(f, text)) continue
                val tmp = File(dir, "$f.tmp"); tmp.writeText(text, Charsets.UTF_8); tmp.renameTo(File(dir, f))
                if (tag.isNotEmpty()) etags.put(f, tag)
                changed++
            } catch (_: Exception) { failed++ }
        }
        if (failed < files.size) m.put("last", System.currentTimeMillis())
        m.put("etags", etags)
        try { meta.writeText(m.toString(), Charsets.UTF_8) } catch (_: Exception) { }
        return JSONObject().put("changed", changed).put("failed", failed)
    }
}
