package org.alpha.hub

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ConfigSync(private val ctx: Context) {
    private val base = "https://huggingface.co/datasets/Kinfemichael/Alpha-links/raw/main/"
    val files = listOf("contacts.json", "books.json", "models.json", "providers.json", "tiers.json")
    private val dir get() = File(ctx.filesDir, "config").apply { mkdirs() }
    private val meta get() = File(dir, "meta.json")
    private val memo = HashMap<String, Pair<Long, String>>()

    @Volatile var version = 0
        private set

    private fun readMeta(): JSONObject = try { JSONObject(meta.readText(Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }

    @Synchronized
    fun cached(name: String): String? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val m = memo[name]
        if (m != null && m.first == f.lastModified() + f.length()) return m.second
        return try { f.readText(Charsets.UTF_8).also { memo[name] = Pair(f.lastModified() + f.length(), it) } } catch (_: Exception) { null }
    }

    fun due(): Boolean {
        val m = readMeta()
        if (System.currentTimeMillis() - m.optLong("last", 0L) > 24L * 3600L * 1000L) return true
        val checked = m.optJSONObject("checked") ?: JSONObject()
        return files.any { !File(dir, it).exists() && !checked.optBoolean(it) }
    }

    @Synchronized
    fun refresh(validate: (String, String) -> Boolean): JSONObject {
        val m = readMeta()
        val etags = m.optJSONObject("etags") ?: JSONObject()
        val checked = m.optJSONObject("checked") ?: JSONObject()
        var changed = 0; var failed = 0
        for (f in files) {
            try {
                val c = URL(base + f).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "AlphaAI/0.1")
                val have = File(dir, f).exists()
                if (have && etags.optString(f).isNotEmpty()) c.setRequestProperty("If-None-Match", etags.optString(f))
                val code = c.responseCode
                if (code == 304) { c.disconnect(); checked.put(f, true); continue }
                if (code == 404) { c.disconnect(); checked.put(f, true); continue }
                if (code != 200) { c.disconnect(); failed++; continue }
                val bytes = c.inputStream.use { it.readBytes() }
                val tag = c.getHeaderField("ETag") ?: ""
                c.disconnect()
                if (bytes.size > 4 * 1024 * 1024) { failed++; continue }
                val text = String(bytes, Charsets.UTF_8)
                if (!validate(f, text)) { failed++; continue }
                val tmp = File(dir, "$f.tmp"); tmp.writeText(text, Charsets.UTF_8); tmp.renameTo(File(dir, f))
                if (tag.isNotEmpty()) etags.put(f, tag)
                checked.put(f, true)
                changed++
            } catch (_: Exception) { failed++ }
        }
        if (failed < files.size) m.put("last", System.currentTimeMillis())
        m.put("etags", etags).put("checked", checked)
        try { meta.writeText(m.toString(), Charsets.UTF_8) } catch (_: Exception) { }
        if (changed > 0) version++
        return JSONObject().put("changed", changed).put("failed", failed)
    }
}
