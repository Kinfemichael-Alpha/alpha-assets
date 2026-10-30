package org.alpha.hub

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * የ AI ውይይት: ታሪክ፣ ቀጥታ (streaming) መልስ፣ "ቀጥል" እና "አቁም"።
 * Chat session: history, live streamed reply, continue and stop.
 * መልሱ እየተመነጨ ያለው ጽሁፍ ወዲያው በ [Job.text] ውስጥ ይከማቻል፤ UI በፍጥነት ይጠይቃል (poll)።
 */
class ChatService(private val ctx: Context, private val settings: SettingsStore, private val engine: OfflineEngine, private val models: ModelManager, private val msg: (String) -> String) {
    class Msg(val role: String, var content: String, var error: Boolean = false)

    /** አንድ የመልስ ሥራ / one reply job */
    class Job(val id: String) {
        @Volatile var text = ""            // እስካሁን የተመነጨው ሙሉ የመልስ ጽሁፍ (ለ"ቀጥል" ቀዳሚውንም ያካትታል)
        @Volatile var status = "running"   // running | done | error
        @Volatile var finish = ""          // STOP | MAX_TOKENS | NETWORK | STOPPED ...
        @Volatile var error = ""           // ወዳጃዊ መልእክት / friendly message
        @Volatile var cancelled = false
        @Volatile var detail = ""
        @Volatile var last = System.currentTimeMillis()
        @Volatile var restore = ""         // መልስ ሳይመጣ ከተሳሰ ጥያቄው ወደ ግብዓት ይመለሳል
    }

    private val client = GeminiClient()
    private val history = ArrayList<Msg>()
    private var job: Job? = null
    private val file = File(ctx.filesDir, "chat_current.json")
    // ለ Gemini የሚላከው ታሪክ በጀት (በግምት ቶከን) / history budget sent to Gemini (estimated tokens)
    private val budget = 12000

    init { load() }

    // ---- ቶከን ግምት: አማርኛ (ኢትዮጵያዊ ፊደል) ~2.5 ቶከን በፊደል፣ ሌላው ~1/3
    private fun estimate(s: String): Int {
        var t = 0.0
        for (ch in s) t += if (ch.code in 0x1200..0x137F) 2.5 else 0.34
        return Math.ceil(t).toInt()
    }
    private fun usage(): Double = history.sumOf { estimate(it.content) + 4 }.toDouble() / budget

    @Synchronized private fun load() {
        try {
            if (!file.exists()) return
            val a = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); history.add(Msg(o.getString("role"), o.getString("content"), o.optBoolean("error"))) }
        } catch (_: Exception) { history.clear() }
    }
    @Synchronized private fun persist() {
        try {
            val a = JSONArray(); for (m in history) a.put(JSONObject().put("role", m.role).put("content", m.content).put("error", m.error))
            val tmp = File(file.path + ".tmp"); tmp.writeText(a.toString(), Charsets.UTF_8); tmp.renameTo(file)
        } catch (_: Exception) { }
    }

    @Synchronized fun view(): JSONObject {
        val a = JSONArray()
        for (m in history) a.put(JSONObject().put("role", m.role).put("content", m.content).put("error", m.error))
        val j = job
        return JSONObject().put("messages", a).put("usage", usage())
            .put("pending", if (j != null && j.status == "running") j.id else JSONObject.NULL)
    }

    @Synchronized fun newChat(): JSONObject {
        if (job?.status == "running") throw ApiError(409, "err_busy")
        history.clear(); persist(); job = null
        return view()
    }

    fun test(): JSONObject {
        val key = settings.getString("gemini_key")
        if (key.isEmpty()) throw ApiError(400, "online_need_key")
        try { return JSONObject().put("ok", true).put("model", client.probe(key)) }
        catch (e: GeminiError) { throw ApiError(502, e.key).also { lastDetail = e.technical } }
    }
    @Volatile var lastDetail = ""

    @Synchronized fun stop() { job?.let { it.cancelled = true; client.abort() } }

    /** ውይይቱን ወደ ፋይል ያስቀምጣል (በስልኩ ላይ ብቻ) / save chat locally */
    @Synchronized fun save(): JSONObject {
        if (history.none { !it.error }) throw ApiError(400, "chat_save_empty")
        val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
        val name = "chat_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        val a = JSONArray(); for (m in history) if (!m.error) a.put(JSONObject().put("role", m.role).put("content", m.content))
        File(dir, name).writeText(JSONObject().put("messages", a).toString(), Charsets.UTF_8)
        return JSONObject().put("ok", true).put("name", name)
    }

    @Synchronized fun send(text: String): JSONObject {
        if (job?.status == "running") throw ApiError(409, "err_busy")
        val clean = text.trim().take(4000)
        if (clean.isEmpty()) throw ApiError(400, "err_bad_request")
        history.add(Msg("user", clean)); history.add(Msg("assistant", ""))
        return startJob(continuation = false, userText = clean)
    }

    /** የተቆረጠን መልስ ከቆመበት ይቀጥላል / continue a cut-off reply from where it stopped */
    @Synchronized fun continueReply(): JSONObject {
        if (job?.status == "running") throw ApiError(409, "err_busy")
        val last = history.lastOrNull()
        if (last == null || last.role != "assistant" || last.content.isEmpty()) throw ApiError(400, "err_bad_request")
        return startJob(continuation = true, userText = "")
    }

    private fun startJob(continuation: Boolean, userText: String): JSONObject {
        val key = settings.getString("gemini_key")
        val j = Job(UUID.randomUUID().toString())
        val target = history.last()                      // መልሱ የሚጻፍበት የ AI መልእክት
        val base = if (continuation) target.content else ""
        j.text = base
        job = j
        if (!settings.getBool("online_mode")) {
            runOffline(j, target, base, continuation, userText)
            return JSONObject().put("job_id", j.id)
        }
        if (key.isEmpty()) {
            fail(j, target, "online_need_key", continuation, userText)
            return JSONObject().put("job_id", j.id)
        }
        val contents = buildContents(continuation)
        val system = systemPrompt()
        val maxTokens = settings.getInt("max_tokens")
        val temp = settings.getDouble("temperature")
        Thread {
            try {
                val r = client.stream(key, system, contents, maxTokens, temp, false, { j.cancelled }) { piece ->
                    j.text += piece; target.content = j.text; j.last = System.currentTimeMillis()    // ወዲያው ለ UI ይገኛል / visible to UI immediately
                }
                synchronized(this) {
                    when {
                        j.cancelled || r.finishReason == "STOPPED" -> { j.finish = "STOPPED"; j.status = "done" }
                        j.text.length == base.length -> {      // ባዶ ምላሽ (የታገደ ወይም ባዶ)
                            fail(j, target, if (r.blocked || r.finishReason == "SAFETY") "online_blocked" else "ai_empty", continuation, userText)
                        }
                        else -> { j.finish = if (r.finishReason == "MAX_TOKENS") "MAX_TOKENS" else "STOP"; j.status = "done" }
                    }
                    if (j.status == "done") persist()
                }
            } catch (e: GeminiError) {
                synchronized(this) {
                    if (j.cancelled) { j.finish = "STOPPED"; j.status = "done"; persist() }
                    else if (j.text.length > base.length) {
                        // የተመነጨው ክፍል ይጠበቃል፤ "ቀጥል" ይቀርባል / keep the partial text, offer "continue"
                        j.finish = "NETWORK"; j.error = msg(e.key); j.status = "done"; persist()
                    } else fail(j, target, e.key, continuation, userText, e.technical)
                }
            } catch (e: Throwable) {
                synchronized(this) { fail(j, target, "err_server", continuation, userText, e.javaClass.simpleName + ": " + (e.message ?: "")) }
            }
        }.start()
        return JSONObject().put("job_id", j.id)
    }

    private fun runOffline(j: Job, target: Msg, base: String, continuation: Boolean, userText: String) {
        val file = models.activeFile()
        if (file == null) { fail(j, target, if (engine.supported) "no_active_model_hint" else "offline_unavailable", continuation, userText); return }
        val temp = settings.getDouble("temperature").toFloat()
        val cfgMax = settings.getInt("max_tokens")
        Thread {
            try {
                engine.load(file)
                val maxTok = minOf(cfgMax, (engine.nCtx / 2).coerceAtLeast(128))
                val filter = ThinkFilter()
                val sink = TokenSink { piece ->
                    val add = filter.feed(piece)
                    if (add.isNotEmpty()) { j.text += add; target.content = j.text; j.last = System.currentTimeMillis() }
                    !j.cancelled
                }
                var budget = engine.nCtx - maxTok - 250
                var rc = -2
                var tries = 0
                while (rc == -2 && tries < 3) {
                    val (roles, contents) = buildOffline(continuation, budget, file.name)
                    rc = engine.generate(roles, contents, maxTok, temp, sink)
                    budget /= 2; tries++
                }
                synchronized(this) {
                    when {
                        j.cancelled || rc == 1 -> { j.finish = "STOPPED"; j.status = "done" }
                        j.text.length == base.length -> fail(j, target, if (rc < 0) "err_server" else "ai_empty", continuation, userText)
                        rc < 0 -> { j.finish = "NETWORK"; j.error = msg("err_server"); j.status = "done" }
                        else -> { j.finish = if (rc == 2) "MAX_TOKENS" else "STOP"; j.status = "done" }
                    }
                    if (j.status == "done") persist()
                }
            } catch (e: OfflineError) {
                synchronized(this) { fail(j, target, e.key, continuation, userText, e.detail); j.error = offlineMessage(e, msg) }
            } catch (e: Throwable) {
                synchronized(this) { fail(j, target, "err_server", continuation, userText, e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun buildOffline(continuation: Boolean, budget: Int, modelName: String): Pair<List<String>, List<String>> {
        val usable = if (continuation) history.toList() else history.dropLast(1)
        val picked = ArrayList<Msg>(); var tokens = 0
        for (m in usable.asReversed()) {
            if (m.error || m.content.isEmpty()) continue
            tokens += estimate(m.content) + 6
            if (tokens > budget && picked.isNotEmpty()) break
            picked.add(m)
        }
        picked.reverse()
        while (picked.isNotEmpty() && picked.first().role != "user") picked.removeAt(0)
        val roles = ArrayList<String>(); val contents = ArrayList<String>()
        var sys = systemPrompt() + " Reply in English."
        if (modelName.contains("qwen3", true)) sys += " /no_think"
        roles.add("system"); contents.add(sys)
        for (m in picked) { roles.add(if (m.role == "user") "user" else "assistant"); contents.add(m.content) }
        if (continuation) { roles.add("user"); contents.add("Continue exactly from where you stopped. Do not repeat anything.") }
        return Pair(roles, contents)
    }

    /** ምንም መልስ ሳይመጣ ሲከሽፍ: ባዶውን መልእክት ያስወግዳል፣ ጥያቄው ወደ ግብዓት ይመለሳል */
    private fun fail(j: Job, target: Msg, key: String, continuation: Boolean, userText: String, detail: String = "") {
        if (!continuation) {
            history.remove(target)
            val u = history.lastOrNull(); if (u != null && u.role == "user" && u.content == userText) history.removeAt(history.size - 1)
            j.restore = userText
        }
        j.error = msg(key); j.detail = detail; j.status = "error"; persist()
    }

    private fun systemPrompt(): String {
        val nick = settings.getString("nickname")
        val long = settings.getString("resp_len") != "short"
        return "You are ALPHA AI, a friendly study assistant for Ethiopian students (high school and university). " +
            "Reply in the same language the user writes in (Amharic or English). Use simple, clear explanations and short examples. " +
            (if (long) "Give complete, well-structured answers. " else "Keep answers short and to the point. ") +
            "Do not use emojis. If you are unsure, say so honestly." +
            (if (nick.isNotEmpty()) " The student's name is $nick; use it only occasionally." else "")
    }

    /** በበጀት ውስጥ የሚገባ የቅርብ ታሪክ ብቻ ይላካል / only the most recent history that fits the budget is sent */
    private fun buildContents(continuation: Boolean): JSONArray {
        val usable = if (continuation) history.toList() else history.dropLast(1)   // ባዶው የ AI መልእክት አይላክም
        val picked = ArrayList<Msg>(); var tokens = 0
        for (m in usable.asReversed()) {
            if (m.error || m.content.isEmpty()) continue
            tokens += estimate(m.content) + 4
            if (tokens > budget && picked.isNotEmpty()) break
            picked.add(m)
        }
        picked.reverse()
        // Gemini ታሪክ በ user መጀመር አለበት / history must begin with a user turn
        while (picked.isNotEmpty() && picked.first().role != "user") picked.removeAt(0)
        val a = JSONArray()
        for (m in picked) a.put(JSONObject().put("role", if (m.role == "user") "user" else "model")
            .put("parts", JSONArray().put(JSONObject().put("text", m.content))))
        if (continuation) a.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
            "Continue exactly from where you stopped. Do not repeat anything and do not add an introduction."))))
        return a
    }

    @Synchronized fun poll(id: String): JSONObject {
        val j = job
        if (j == null || j.id != id) throw ApiError(404, "err_not_found")
        if (j.status == "running" && System.currentTimeMillis() - j.last > 90000) {
            j.cancelled = true; client.abort()
            if (j.text.isEmpty()) { history.lastOrNull()?.let { if (it.role == "assistant" && it.content.isEmpty()) history.remove(it) }; j.error = msg("online_unreachable"); j.status = "error" }
            else { j.finish = "NETWORK"; j.error = msg("online_unreachable"); j.status = "done" }
            persist()
        }
        val o = JSONObject().put("status", j.status).put("text", j.text).put("finish", j.finish)
            .put("error", j.error).put("detail", j.detail).put("restore", j.restore).put("usage", usage())
        o.put("warn", usage() >= 0.8)
        return o
    }
}
