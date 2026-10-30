package org.alpha.hub

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * የ UI "/api/..." ጥሪዎች ማዘዋወሪያ። ቀድሞ በ Python ሰርቨር (api.py) ይሰራ የነበረው ስራ ወደዚህ እየተሸጋገረ ነው።
 * Router for the UI's API calls; replaces the old Python server endpoint by endpoint.
 *
 * ይመልሳል / returns: Pair(httpStatus, jsonBody)
 */
class ApiError(val status: Int, val key: String) : Exception(key)

class ApiRouter(private val ctx: Context) {
    private val settings = SettingsStore(ctx)
    private val i18n: JSONObject = readAsset("i18n.json")
    private val themes: JSONArray = JSONArray(ctx.assets.open("themes.json").bufferedReader(Charsets.UTF_8).readText())

    private fun readAsset(name: String) = JSONObject(ctx.assets.open(name).bufferedReader(Charsets.UTF_8).readText())

    /** የስህተት መልእክት በአሁኑ ቋንቋ (ቁልፍ ከ i18n) / friendly error text in the current language */
    private fun msg(key: String): String =
        i18n.optJSONObject(settings.getString("language"))?.optString(key)?.takeIf { it.isNotEmpty() } ?: key

    private val downloader = FileDownloader { e ->
        // ወዳጃዊ መልእክት: ቴክኒካዊ ጽሁፍ፣ URL ወይም የፋይል ቦታ ለተጠቃሚ አይታይም
        if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException)
            msg("online_no_internet") else msg("dl_failed").replace("{err}", "").trim().trimEnd(':').trim()
    }
    private val catalogCache = HashMap<String, List<LibGroup>>()
    // የ AI ውይይት (Gemini ቀጥታ መልስ) / AI chat with live Gemini streaming
    private val engine = OfflineEngine(ctx)
    private val models = ModelManager(ctx, settings, engine) { msg(it) }
    private val chat = ChatService(ctx, settings, engine, models) { msg(it) }
    private val study = StudyService(ctx, settings) { msg(it) }

    fun handle(method: String, fullPath: String, body: JSONObject): Pair<Int, JSONObject> {
        val path = fullPath.substringBefore('?')
        // GET ጥያቄ query (?kind=books) ወደ body ይዋሃዳል / merge query params into body
        val q = fullPath.substringAfter('?', "")
        if (q.isNotEmpty()) for (kv in q.split('&')) {
            val k = java.net.URLDecoder.decode(kv.substringBefore('='), "UTF-8")
            if (k.isNotEmpty()) body.put(k, java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8"))
        }
        return try {
            Pair(200, route(method, path, body))
        } catch (e: ApiError) {
            Pair(e.status, JSONObject().put("error", e.key).put("message", msg(e.key) + chat.lastDetail.let { if (e.status == 502 && it.isNotEmpty()) "\n($it)" else "" }))
        } catch (e: Exception) {
            Pair(500, JSONObject().put("error", "server_error").put("message", msg("err_server")))
        }
    }

    private fun route(method: String, path: String, b: JSONObject): JSONObject = when ("$method $path") {
        "GET /api/ping" -> JSONObject().put("ok", true).put("version", APP_VERSION)
        "GET /api/state" -> state()
        "GET /api/i18n" -> JSONObject().put("strings", i18n)
        "POST /api/profile" -> profile(b)
        "POST /api/settings" -> updateSettings(b)
        "POST /api/settings/reset" -> { settings.reset(); JSONObject().put("settings", settingsView()) }
        "GET /api/ads/status" -> adsStatus()
        "POST /api/ads/banner" -> JSONObject().put("ok", true)
        "GET /api/library" -> libraryList(b)
        "POST /api/library/download" -> libraryDownload(b)
        "GET /api/download" -> downloader.snapshot()
        "POST /api/download/cancel" -> { downloader.cancel(); JSONObject().put("ok", true) }
        "POST /api/library/read" -> libraryRead(b)
        "POST /api/library/delete" -> libraryDelete(b)
        // ---- AI ውይይት
        "POST /api/ai/enter" -> aiEnter()
        "POST /api/ai/leave" -> { chat.stop(); engine.unload(); JSONObject().put("ok", true) }
        "POST /api/ai/test" -> chat.test()
        "GET /api/chat" -> chat.view()
        "POST /api/chat/send" -> chat.send(b.optString("text"))
        "POST /api/chat/continue" -> chat.continueReply()
        "POST /api/chat/stop" -> { chat.stop(); JSONObject().put("ok", true) }
        "GET /api/chat/poll" -> chat.poll(b.optString("job_id"))
        "POST /api/chat/new" -> chat.newChat()
        "POST /api/chat/save" -> chatSave()
        // ቅንብሮች ማያ የሚጠይቀው ዝርዝር (ሞዴሎች ገና አልተሰሩም - ባዶ ዝርዝር) / settings screen list; models not ported yet
        "GET /api/job" -> study.job(b.optString("id"))
        "GET /api/quiz/list" -> study.quizList()
        "GET /api/quiz/get" -> study.quizGet(b)
        "POST /api/quiz/generate" -> study.quizGenerate(b)
        "POST /api/quiz/score" -> study.quizScore(b)
        "POST /api/quiz/delete" -> study.quizDelete(b)
        "GET /api/notes/list" -> study.notesList(adsStatus())
        "GET /api/notes/get" -> study.noteGet(b)
        "POST /api/notes/generate" -> study.noteGenerate(b)
        "POST /api/notes/save" -> study.noteSave(b)
        "POST /api/notes/delete" -> study.noteDelete(b)
        "GET /api/progress" -> study.progress()
        "POST /api/progress/reset" -> study.progressReset()
        "GET /api/models" -> models.list().put("settings", settingsView()).put("download", downloader.snapshot())
        "POST /api/models/download" -> modelDownload(b)
        "POST /api/models/activate" -> { models.activate(b.optString("name")); JSONObject().put("settings", settingsView()) }
        "POST /api/models/deactivate" -> { models.deactivate(); JSONObject().put("settings", settingsView()) }
        "POST /api/models/delete" -> { models.delete(b.optString("name")); JSONObject().put("settings", settingsView()) }
        "POST /api/sync" -> study.runJob { JSONObject() }
        // ገና ያልተሰሩ ክፍሎች / not yet ported
        else -> throw ApiError(501, "err_not_ready")
    }

    // ---- state
    private fun themeByName(name: String): JSONObject? {
        for (i in 0 until themes.length()) if (themes.getJSONObject(i).optString("name") == name) return themes.getJSONObject(i)
        return null
    }

    private fun settingsView(): JSONObject {
        var theme = settings.getString("theme")
        if (themeByName(theme) == null) theme = "Blue"
        return JSONObject()
            .put("theme", theme)
            .put("palette", themeByName(theme))
            .put("online_mode", settings.getBool("online_mode"))
            .put("appearance", settings.getString("appearance"))
            .put("language", settings.getString("language"))
            .put("active_model", models.activeView())
            // ቁልፉ ወደ UI አይመለስም፤ መኖሩና የመጨረሻ 4 ቁምፊ ብቻ / the key itself never goes back to the UI
            .put("gemini_key_set", settings.getString("gemini_key").isNotEmpty())
            .put("gemini_key_tail", settings.getString("gemini_key").takeLast(4))
            .put("max_tokens", settings.getInt("max_tokens"))
            .put("temperature", settings.getDouble("temperature"))
            .put("resp_len", settings.getString("resp_len"))
    }

    private fun aiEnter(): JSONObject {
        if (settings.getBool("online_mode")) return JSONObject().put("mode", "online").put("ready", true).put("locked", false)
        val off = JSONObject().put("mode", "offline").put("ready", false)
        if (!engine.supported) return off.put("message", msg("offline_unavailable"))
        val f = models.activeFile() ?: return off.put("message", msg("no_active_model_hint"))
        if (engine.isLoaded(f.path)) return off.put("ready", true).put("locked", false)
        return off.put("job_id", study.runJob { engine.load(f); JSONObject() }.getString("job_id"))
    }

    private fun modelDownload(b: JSONObject): JSONObject {
        val e = models.entry(b.optString("filename")) ?: throw ApiError(404, "err_not_found")
        if (!downloader.start(e.url, File(models.dir, e.filename), e.filename, models.cleanTitle(e.filename), "public")) throw ApiError(409, "dl_busy")
        return JSONObject().put("ok", true)
    }

    private fun chatSave(): JSONObject {
        val r = chat.save()
        return r.put("message", msg("chat_saved_ok"))
    }

    private fun state(): JSONObject = JSONObject()
        .put("version", APP_VERSION)
        .put("nickname", settings.getString("nickname"))
        .put("onboarded", settings.getBool("onboarded"))
        .put("settings", settingsView())
        .put("themes", themes)
        .put("ads", adsStatus())

    /** v0: AI ነጻ ነው (በማስታወቂያ አይዘጋም) - የማስታወቂያ ስርዓት በኋላ ይገባል / AI is free; real ads come later */
    private fun adsStatus(): JSONObject = JSONObject()
        .put("active", true).put("remaining", 86400).put("next_minutes", 0)
        .put("provider", "none").put("banner_dp", 0).put("fake_banner", false)

    private fun profile(b: JSONObject): JSONObject {
        if (b.has("nickname")) {
            val nick = b.optString("nickname").filter { !it.isISOControl() }.trim().take(40)
            settings.set("nickname", nick)
        }
        if (b.optBoolean("onboarded", false)) settings.set("onboarded", true)
        return JSONObject().put("nickname", settings.getString("nickname")).put("onboarded", settings.getBool("onboarded"))
    }

    private fun updateSettings(b: JSONObject): JSONObject {
        if (b.has("theme")) {
            val t = b.optString("theme")
            if (themeByName(t) == null) throw ApiError(400, "err_bad_request")
            settings.set("theme", t)
        }
        if (b.has("appearance")) {
            val a = b.optString("appearance")
            if (a != "light" && a != "dark" && a != "device") throw ApiError(400, "err_bad_request")
            settings.set("appearance", a)
        }
        if (b.has("online_mode")) {
            if (b.opt("online_mode") !is Boolean) throw ApiError(400, "err_bad_request")
            settings.set("online_mode", b.getBoolean("online_mode"))
        }
        if (b.has("gemini_key")) {
            val k = b.optString("gemini_key").filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            if (k.length > 200) throw ApiError(400, "err_bad_request")
            settings.set("gemini_key", k)
        }
        if (b.has("max_tokens")) {
            val n = b.optInt("max_tokens", -1)
            if (n < 256 || n > 8192) throw ApiError(400, "err_bad_request")
            settings.set("max_tokens", n)
        }
        if (b.has("temperature")) {
            val d = b.optDouble("temperature", -1.0)
            if (d.isNaN() || d < 0.0 || d > 1.0) throw ApiError(400, "err_bad_request")
            settings.set("temperature", d)
        }
        if (b.has("resp_len")) {
            val r = b.optString("resp_len")
            if (r != "short" && r != "long") throw ApiError(400, "err_bad_request")
            settings.set("resp_len", r)
        }
        if (b.has("language")) {
            val l = b.optString("language")
            if (l != "AM" && l != "EN") throw ApiError(400, "err_bad_request")
            settings.set("language", l)
        }
        return JSONObject().put("settings", settingsView())
    }

    // ---- library (መጻሕፍት + ፈተናዎች)
    private fun kindOf(b: JSONObject): String {
        val k = b.optString("kind"); if (k != "books" && k != "exams") throw ApiError(400, "err_bad_request"); return k
    }
    private fun libDir(kind: String) = File(ctx.filesDir, kind).apply { mkdirs() }
    private fun catalog(kind: String) = catalogCache.getOrPut(kind) { LibraryCatalog.load(ctx, kind) }
    private fun libFile(kind: String, name: String): File {
        if (!name.endsWith(".pdf") || name.contains('/') || name.contains('\\')) throw ApiError(400, "err_bad_request")
        return File(libDir(kind), name)
    }

    private fun libraryList(b: JSONObject): JSONObject {
        val kind = kindOf(b)
        val have = libDir(kind).listFiles { f -> f.name.endsWith(".pdf") }?.sortedBy { it.name } ?: emptyList()
        val haveNames = have.map { it.name }.toSet()
        val dl = JSONArray()
        for (f in have) dl.put(JSONObject().put("name", f.name).put("title", f.name.removeSuffix(".pdf"))
            .put("size_mb", Math.round(f.length() / 104857.6) / 10.0))
        val cat = JSONArray()
        for (g in catalog(kind)) {
            val es = JSONArray()
            for (e in g.entries) es.put(JSONObject().put("filename", e.filename).put("title", e.title)
                .put("size_mb", JSONObject.NULL).put("downloaded", e.filename in haveNames))
            cat.put(JSONObject().put("group", g.group).put("entries", es))
        }
        return JSONObject().put("kind", kind).put("downloaded", dl).put("catalog", cat).put("download", downloader.snapshot())
    }

    private fun libraryDownload(b: JSONObject): JSONObject {
        val kind = kindOf(b)
        val fn = b.optString("filename")
        // URL ከዝርዝሩ ብቻ ይወሰዳል፣ ከ UI አይቀበልም / URL comes only from the catalog, never from the UI
        val entry = catalog(kind).flatMap { it.entries }.firstOrNull { it.filename == fn } ?: throw ApiError(404, "err_not_found")
        if (!downloader.start(entry.url, File(libDir(kind), entry.filename), entry.filename, entry.title, kind)) throw ApiError(409, "dl_busy")
        return JSONObject().put("ok", true)
    }

    private fun libraryRead(b: JSONObject): JSONObject {
        val f = libFile(kindOf(b), b.optString("name"))
        if (!f.exists()) throw ApiError(404, "err_not_found")
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) { throw ApiError(500, "pdf_open_failed") }
        return JSONObject().put("ok", true)
    }

    private fun libraryDelete(b: JSONObject): JSONObject {
        val f = libFile(kindOf(b), b.optString("name"))
        var removed = false
        for (x in listOf(f, File(f.path + ".part"))) if (x.exists()) { x.delete(); removed = true }
        if (!removed) throw ApiError(404, "err_not_found")
        return JSONObject().put("ok", true)
    }

    companion object { const val APP_VERSION = "0.1" }
}
