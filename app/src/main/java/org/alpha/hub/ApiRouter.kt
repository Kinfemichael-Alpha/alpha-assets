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
    var onExit: (() -> Unit)? = null
    var ads: AdBridge? = null
    private val sync = ConfigSync(ctx)
    val settings = SettingsStore(ctx)
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
    private val engine = OfflineEngine(ctx).also { it.turbo = { System.currentTimeMillis() < settings.getLong("turbo_until") } }
    private val models = ModelManager(ctx, settings, engine) { msg(it) }
    private val llm = LlmService(ctx, settings)
    private val chat = ChatService(ctx, settings, llm, engine, models) { msg(it) }
    private val study = StudyService(ctx, settings, llm, engine, models) { msg(it) }

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
            Pair(e.status, JSONObject().put("error", e.key).put("message", msg(e.key) + chat.lastDetail.let { if (e.status == 502 && it.isNotEmpty() && settings.getBool("debug_details")) "\n($it)" else "" }))
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
        "POST /api/ads/banner" -> { ads?.setBanner(b.optBoolean("visible", true)); JSONObject().put("ok", true) }
        "GET /api/library" -> libraryList(b)
        "POST /api/library/download" -> libraryDownload(b)
        "GET /api/download" -> downloader.snapshot()
        "POST /api/download/cancel" -> { downloader.cancel(); JSONObject().put("ok", true) }
        "POST /api/library/read" -> libraryRead(b)
        "POST /api/library/delete" -> libraryDelete(b)
        // ---- AI ውይይት
        "POST /api/ai/enter" -> aiEnter()
        "POST /api/ai/leave" -> { chat.stop(); engine.unload(); JSONObject().put("ok", true) }
        "GET /api/providers" -> JSONObject().put("providers", llm.providers())
        "POST /api/provider/save" -> { llm.save(b); JSONObject().put("providers", llm.providers()).put("settings", settingsView()) }
        "POST /api/provider/select" -> { llm.select(b.optString("id")); JSONObject().put("providers", llm.providers()).put("settings", settingsView()) }
        "POST /api/provider/models" -> providerModels(b)
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
        "POST /api/models/import" -> modelImport()
        "POST /api/models/activate" -> { models.activate(b.optString("name")); JSONObject().put("settings", settingsView()) }
        "POST /api/models/deactivate" -> { models.deactivate(); JSONObject().put("settings", settingsView()) }
        "POST /api/models/delete" -> { models.delete(b.optString("name")); JSONObject().put("settings", settingsView()) }
        "POST /api/sync" -> study.runJob { syncNow() }
        "GET /api/contacts" -> contacts()
        "POST /api/contacts/open" -> contactOpen(b)
        "POST /api/exit" -> { chat.stop(); engine.unload(); onExit?.invoke(); JSONObject().put("ok", true) }
        "POST /api/ads/rewarded" -> study.runJob { rewardedAd() }
        "POST /api/ads/interstitial" -> interstitialAd()
        "POST /api/ads/spend" -> adSpend(b)
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
            .put("provider", llm.activeId())
            .put("gemini_key_set", llm.configured())
            .put("gemini_key_tail", settings.getString("gemini_key").takeLast(4))
            .put("max_tokens", settings.getInt("max_tokens"))
            .put("temperature", settings.getDouble("temperature"))
            .put("resp_len", settings.getString("resp_len"))
            .put("debug_details", settings.getBool("debug_details"))
    }

    private fun syncNow(): JSONObject {
        val r = sync.refresh { name, text ->
            if (name == "books.json" || name == "exams.json") try { LibraryCatalog.parse(JSONObject(text)).isNotEmpty() } catch (_: Exception) { false } else true
        }
        catalogCache.clear()
        return r
    }

    init { Thread { try { if (sync.due()) syncNow() } catch (_: Exception) { } }.apply { isDaemon = true }.start() }

    private val phoneRe = Regex("^\\+?[0-9][0-9 \\-()]{5,19}$")
    private val tgRe = Regex("^@?[A-Za-z0-9_]{4,32}$")
    private val mailRe = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    private fun contacts(): JSONObject {
        val o = try { JSONObject(sync.cached("contacts.json") ?: throw Exception()) } catch (_: Exception) { readAsset("contacts.json") }
        fun list(k: String, re: Regex?): JSONArray {
            val raw = o.opt(k); val out = JSONArray()
            val items = when (raw) { is JSONArray -> (0 until raw.length()).map { raw.optString(it) }; is String -> listOf(raw); else -> emptyList() }
            for (v in items) { val t = v.trim(); if (t.isNotEmpty() && (re == null || re.matches(t))) out.put(t) }
            return out
        }
        return JSONObject().put("name", list("name", null)).put("phone", list("phone", phoneRe)).put("telegram", list("telegram", tgRe)).put("email", list("email", mailRe))
    }

    private fun contactOpen(b: JSONObject): JSONObject {
        val kind = b.optString("kind"); val value = b.optString("value").trim()
        if (kind != "phone" && kind != "telegram" && kind != "email") throw ApiError(400, "err_bad_request")
        val list = contacts().getJSONArray(kind)
        if ((0 until list.length()).none { list.getString(it) == value }) throw ApiError(404, "err_not_found")
        val i = when (kind) {
            "phone" -> Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + value.replace(Regex("[\\s\\-()]"), "")))
            "telegram" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/" + value.trimStart('@')))
            else -> Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:$value"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { throw ApiError(500, "err_open_failed") }
        return JSONObject().put("ok", true)
    }

    private fun providerModels(b: JSONObject): JSONObject {
        try { return JSONObject().put("models", JSONArray(llm.listModels(b.optString("id")))) }
        catch (e: GeminiError) { throw ApiError(502, e.key).also { chat.lastDetail = e.technical } }
    }

    private fun aiEnter(): JSONObject {
        if (settings.getBool("online_mode")) return JSONObject().put("mode", "online").put("ready", true).put("locked", false)
        val off = JSONObject().put("mode", "offline").put("ready", false)
        if (!engine.supported) return off.put("message", msg("offline_unavailable"))
        val f = models.activeFile() ?: return off.put("message", msg("no_active_model_hint"))
        if (engine.isLoaded(f.path)) return off.put("ready", true).put("locked", false)
        return off.put("job_id", study.runJob { engine.load(f); JSONObject() }.getString("job_id"))
    }

    private class PendingImport(val uri: android.net.Uri, val name: String, val size: Long)
    @Volatile private var pendingImport: PendingImport? = null

    fun prepareImport(uri: android.net.Uri): JSONObject {
        var name = ""; var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: ""
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val magic = ByteArray(4)
            val n = ctx.contentResolver.openInputStream(uri)?.use { it.read(magic) } ?: -1
            if (n != 4 || String(magic, Charsets.US_ASCII) != "GGUF") return JSONObject().put("ok", false).put("message", msg("import_not_gguf"))
        } catch (e: Exception) { return JSONObject().put("ok", false).put("message", msg("err_open_failed")) }
        var safe = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("").trim('.', '_')
        if (safe.isEmpty()) safe = "model"
        if (!safe.endsWith(".gguf", true)) safe += ".gguf"
        pendingImport = PendingImport(uri, safe, size)
        return JSONObject().put("ok", true).put("name", safe).put("title", models.cleanTitle(safe)).put("size_mb", if (size > 0) Math.round(size / 1048576.0) else JSONObject.NULL)
    }

    private fun modelImport(): JSONObject {
        val p = pendingImport ?: throw ApiError(400, "err_bad_request")
        val dest = File(models.dir, p.name)
        if (dest.exists()) throw ApiError(409, "import_exists")
        if (p.size > 0 && models.dir.usableSpace < p.size + 200L * 1048576L) throw ApiError(507, "import_no_space")
        val ok = downloader.startCopy({ ctx.contentResolver.openInputStream(p.uri) ?: throw java.io.IOException("open") }, p.size, dest, p.name, models.cleanTitle(p.name), "public")
        if (!ok) throw ApiError(409, "dl_busy")
        pendingImport = null
        return JSONObject().put("ok", true)
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

    private fun adsStatus(): JSONObject {
        val now = System.currentTimeMillis()
        return JSONObject().put("active", true).put("remaining", 86400).put("next_minutes", 0)
            .put("provider", "admob").put("banner_dp", 0).put("fake_banner", false)
            .put("points", settings.getInt("points"))
            .put("skip_left", maxOf(0L, (settings.getLong("skip_until") - now) / 1000L))
            .put("turbo_left", maxOf(0L, (settings.getLong("turbo_until") - now) / 1000L))
    }

    private fun rewardedAd(): JSONObject {
        val bridge = ads ?: return JSONObject().put("source", "none").put("points", settings.getInt("points"))
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        bridge.showRewarded { ok = it; latch.countDown() }
        latch.await(240, java.util.concurrent.TimeUnit.SECONDS)
        if (ok) settings.set("points", settings.getInt("points") + 1)
        return JSONObject().put("source", if (ok) "ad" else "none").put("points", settings.getInt("points"))
    }

    private fun interstitialAd(): JSONObject {
        val now = System.currentTimeMillis()
        val bridge = ads
        val allowed = bridge != null && bridge.isOnline() && now > settings.getLong("skip_until") && now - settings.getLong("last_inter") > 180000L
        if (!allowed) return JSONObject().put("shown", false)
        settings.set("last_inter", now)
        bridge!!.showInterstitial { }
        return JSONObject().put("shown", true)
    }

    private fun adSpend(b: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val pts = settings.getInt("points")
        val item = b.optString("item")
        val cost = when (item) { "skip" -> 1; "turbo" -> 2; else -> throw ApiError(400, "err_bad_request") }
        if (pts < cost) throw ApiError(402, "points_low")
        settings.set("points", pts - cost)
        if (item == "skip") settings.set("skip_until", maxOf(now, settings.getLong("skip_until")) + 3 * 60000L)
        else settings.set("turbo_until", maxOf(now, settings.getLong("turbo_until")) + 10 * 60000L)
        return adsStatus()
    }

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
        if (b.has("debug_details")) {
            if (b.opt("debug_details") !is Boolean) throw ApiError(400, "err_bad_request")
            settings.set("debug_details", b.getBoolean("debug_details"))
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
    private fun catalog(kind: String) = catalogCache.getOrPut(kind) { LibraryCatalog.load(ctx, kind, sync.cached("$kind.json")) }
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
            cat.put(JSONObject().put("group", g.group).put("top", g.top).put("subject", g.subject).put("entries", es))
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
