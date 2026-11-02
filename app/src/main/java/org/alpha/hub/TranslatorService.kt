package org.alpha.hub

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class TranslatorService(ctx: Context, private val settings: SettingsStore, private val engine: OfflineEngine, private val downloader: FileDownloader) {
    val dir: File get() = File(appDir, "translators").apply { mkdirs() }
    private val appDir = ctx.filesDir
    @Volatile var running = false
        private set

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun cleanTitle(fn: String) = fn.removeSuffix(".gguf").replace('-', ' ').replace('_', ' ')

    private fun safeFile(name: String): File {
        if (!name.endsWith(".gguf") || name.contains('/') || name.contains('\\') || name.contains("..")) throw ApiError(400, "err_bad_request")
        return File(dir, name)
    }

    fun activeFile(): File? {
        val n = settings.getString("active_translator")
        if (n.isEmpty()) return null
        return File(dir, n).takeIf { it.exists() }
    }

    fun list(): JSONObject {
        val active = settings.getString("active_translator")
        val a = JSONArray()
        (dir.listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()).forEach {
            val coded = it.name.startsWith("tr_")
            a.put(JSONObject().put("name", it.name)
                .put("title", if (coded) "Translation model " + it.name.removePrefix("tr_").removeSuffix(".gguf").take(4) else cleanTitle(it.name))
                .put("size_mb", Math.round(it.length() / 1048576.0)).put("active", it.name == active))
        }
        return JSONObject().put("downloaded", a).put("running", running)
    }

    fun download(input: String): JSONObject {
        val url = UrlCodec.decode(input.trim()) ?: throw ApiError(400, "tr_url_bad")
        val coded = !input.trim().startsWith("https://")
        val path = url.substringBefore('?').substringBefore('#')
        val host = try { java.net.URL(url).host } catch (_: Exception) { "" }
        if (host.isEmpty()) throw ApiError(400, "tr_url_bad")
        val fname: String
        val title: String
        if (coded) {
            fname = "tr_" + sha(url).take(10) + ".gguf"
            title = "Translation model " + fname.removePrefix("tr_").take(4)
        } else {
            val last = java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
            if (!last.endsWith(".gguf", true)) throw ApiError(400, "tr_url_bad")
            fname = last.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100)
            title = cleanTitle(fname)
        }
        val dest = File(dir, fname)
        if (dest.exists()) throw ApiError(409, "import_exists")
        if (!downloader.start(url, dest, fname, title, "translator")) throw ApiError(409, "dl_busy")
        return JSONObject().put("ok", true)
    }

    fun activate(name: String) {
        val f = safeFile(name)
        if (!f.exists()) throw ApiError(404, "err_not_found")
        settings.set("active_translator", name)
    }

    fun deactivate() { settings.set("active_translator", "") }

    fun delete(name: String) {
        val f = safeFile(name)
        var removed = false
        for (x in listOf(f, File(f.path + ".part"))) if (x.exists()) { x.delete(); removed = true }
        if (!removed) throw ApiError(404, "err_not_found")
        if (settings.getString("active_translator") == name) deactivate()
    }

    private fun autoTarget(text: String): String {
        var eth = 0; var letters = 0
        for (c in text) if (c.isLetter()) { letters++; if (c.code in 0x1200..0x137F) eth++ }
        return if (letters > 0 && eth * 10 > letters * 3) "en" else "am"
    }

    private val protectRe = Regex("```[\\s\\S]*?```|\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$\\$[\\s\\S]*?\\$\\$|`[^`\\n]+`")

    private fun chunks(s: String, limit: Int = 700): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out.add(cur.toString().trim()); cur = StringBuilder() }
        for (para in s.split(Regex("\\n\\s*\\n"))) {
            var p = para.trim()
            if (p.isEmpty()) continue
            while (p.length > limit) {
                var cut = p.lastIndexOfAny(charArrayOf('.', '?', '!', '።', '፧', '\n'), limit)
                if (cut < limit / 3) cut = p.lastIndexOf(' ', limit)
                if (cut < limit / 3) cut = limit
                flush()
                out.add(p.substring(0, cut + 1).trim())
                p = p.substring(cut + 1).trim()
            }
            if (cur.length + p.length + 2 > limit) flush()
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(p)
        }
        flush()
        return out
    }

    fun translate(text: String, toIn: String, phase: (String) -> Unit): String {
        val file = activeFile() ?: throw ApiError(400, "tr_no_model")
        if (!engine.supported) throw ApiError(400, "offline_unavailable")
        val src = text.trim()
        if (src.isEmpty()) throw ApiError(400, "err_bad_request")
        val to = if (toIn == "en" || toIn == "am") toIn else autoTarget(src)
        val lang = if (to == "en") "English" else "Amharic"
        val saved = ArrayList<String>()
        val shielded = protectRe.replace(src) { m -> saved.add(m.value); "⟦" + (saved.size - 1) + "⟧" }
        val parts = chunks(shielded)
        val template = settings.getString("translator_prompt").ifEmpty { DEFAULT_PROMPT }
        running = true
        try {
            phase("loading")
            engine.load(file)
            phase("")
            val outParts = ArrayList<String>()
            for (part in parts) {
                val filter = ThinkFilter()
                val sb = StringBuilder()
                val prompt = template.replace("{lang}", lang).replace("{text}", part)
                val maxTok = minOf(1024, (engine.nCtx / 2).coerceAtLeast(128))
                engine.generate(listOf("user"), listOf(prompt), maxTok, 0.1f, TokenSink { sb.append(filter.feed(it)); true })
                outParts.add(sb.toString().trim())
            }
            var result = outParts.joinToString("\n\n").trim()
            if (result.isEmpty()) throw ApiError(500, "tr_failed")
            for (i in saved.indices) result = result.replace("⟦$i⟧", saved[i])
            return result
        } finally {
            running = false
            engine.unload()
        }
    }

    companion object { const val DEFAULT_PROMPT = "Translate the following text to {lang}. Reply with the translation only.\n\n{text}" }
}
