package org.alpha.hub

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class ModelManager(private val ctx: Context, private val settings: SettingsStore, private val engine: OfflineEngine, private val msg: (String) -> String) {
    class Entry(val title: String, val filename: String, val url: String, val sizeMb: Int, val minRamGb: Int)

    val dir: File get() = File(ctx.filesDir, "models").apply { mkdirs() }

    private val catalog: List<Entry> by lazy {
        val a = JSONArray(ctx.assets.open("models.json").bufferedReader(Charsets.UTF_8).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); Entry(o.getString("title"), o.getString("filename"), o.getString("url"), o.getInt("size_mb"), o.optInt("min_ram_gb", 4)) }
    }

    fun entry(filename: String): Entry? = catalog.firstOrNull { it.filename == filename }

    fun cleanTitle(fn: String) = fn.removeSuffix(".gguf").replace('-', ' ').replace('_', ' ')

    fun safeFile(name: String): File {
        if (!name.endsWith(".gguf") || name.contains('/') || name.contains('\\') || name.contains("..")) throw ApiError(400, "err_bad_request")
        return File(dir, name)
    }

    fun activeFile(): File? {
        val n = settings.getString("active_model")
        if (n.isEmpty()) return null
        val f = File(dir, n)
        return if (f.exists()) f else null
    }

    fun activeView(): Any {
        val f = activeFile() ?: return JSONObject.NULL
        return JSONObject().put("name", f.name).put("title", cleanTitle(f.name))
    }

    private fun entryView(e: Entry) = JSONObject().put("filename", e.filename).put("title", cleanTitle(e.filename))
        .put("size_mb", e.sizeMb).put("downloaded", File(dir, e.filename).exists())
        .put("fits", engine.fits(e.sizeMb, e.minRamGb)).put("note", if (engine.fits(e.sizeMb, e.minRamGb)) "" else msg("model_too_big"))

    fun list(): JSONObject {
        val active = settings.getString("active_model")
        val have = JSONArray()
        (dir.listFiles { f -> f.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()).forEach {
            have.put(JSONObject().put("name", it.name).put("scope", "public").put("title", cleanTitle(it.name))
                .put("size_mb", Math.round(it.length() / 1048576.0)).put("active", it.name == active))
        }
        val rec = JSONArray(); val all = JSONArray()
        for (e in catalog) { all.put(entryView(e)); if (engine.fits(e.sizeMb, e.minRamGb)) rec.put(entryView(e)) }
        val groups = JSONArray()
        if (rec.length() > 0) groups.put(JSONObject().put("group", msg("models_recommended")).put("entries", rec))
        groups.put(JSONObject().put("group", msg("models_advanced")).put("entries", all))
        return JSONObject().put("downloaded", have).put("public", groups)
    }

    fun activate(name: String) {
        val f = safeFile(name)
        if (!f.exists()) throw ApiError(404, "err_not_found")
        engine.unload()
        settings.set("active_model", name)
    }

    fun deactivate() { engine.unload(); settings.set("active_model", "") }

    fun delete(name: String) {
        val f = safeFile(name)
        var removed = false
        for (x in listOf(f, File(f.path + ".part"))) if (x.exists()) { x.delete(); removed = true }
        if (!removed) throw ApiError(404, "err_not_found")
        if (settings.getString("active_model") == name) deactivate()
    }
}
