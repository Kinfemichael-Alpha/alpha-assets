package org.alpha.hub

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class StudyService(ctx: Context, private val settings: SettingsStore, private val llm: LlmService, private val msg: (String) -> String) {
    class JobState { @Volatile var state = "running"; @Volatile var result: JSONObject? = null; @Volatile var error = "" }

    private val jobs = ConcurrentHashMap<String, JobState>()
    private val quizFile = File(ctx.filesDir, "quizzes.json")
    private val noteFile = File(ctx.filesDir, "notes.json")
    private val scoreFile = File(ctx.filesDir, "scores.json")
    private val maxQuestions = 30
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun readArr(f: File): JSONArray = try { if (f.exists()) JSONArray(f.readText(Charsets.UTF_8)) else JSONArray() } catch (_: Exception) { JSONArray() }
    private fun writeArr(f: File, a: JSONArray) {
        val tmp = File(f.path + ".tmp"); tmp.writeText(a.toString(), Charsets.UTF_8); tmp.renameTo(f)
    }
    private fun nextId(a: JSONArray): Int { var m = 0; for (i in 0 until a.length()) m = maxOf(m, a.getJSONObject(i).optInt("id")); return m + 1 }
    private fun today() = dayFmt.format(Date())
    private fun hasEthiopic(s: String) = s.any { it.code in 0x1200..0x137F }

    fun job(id: String): JSONObject {
        val j = jobs[id] ?: throw ApiError(404, "err_not_found")
        val o = JSONObject().put("state", j.state).put("status", j.state).put("error", j.error)
        j.result?.let { o.put("result", it) }
        return o
    }

    fun runJob(work: (JobState) -> JSONObject): JSONObject {
        val id = UUID.randomUUID().toString()
        val j = JobState(); jobs[id] = j
        Thread {
            try { j.result = work(j); j.state = "done" }
            catch (e: GeminiError) { j.error = msg(e.key); j.state = "error" }
            catch (e: ApiError) { j.error = msg(e.key); j.state = "error" }
            catch (e: OfflineError) { j.error = offlineMessage(e, msg); j.state = "error" }
            catch (e: Exception) { j.error = msg("err_server"); j.state = "error" }
        }.start()
        return JSONObject().put("job_id", id)
    }

    private fun requireKey(): String {
        if (!settings.getBool("online_mode")) throw ApiError(400, "offline_soon")
        if (!llm.configured()) throw ApiError(400, "online_need_key")
        return "ok"
    }

    private fun ask(key: String, system: String, user: String, maxTokens: Int, temperature: Double, json: Boolean): String {
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))))
        val sb = StringBuilder()
        llm.stream(system, contents, maxTokens, temperature, json, { false }) { sb.append(it) }
        return sb.toString()
    }

    fun quizList(): JSONObject {
        val a = readArr(quizFile); val out = JSONArray()
        for (i in a.length() - 1 downTo 0) {
            val q = a.getJSONObject(i)
            out.put(JSONObject().put("id", q.getInt("id")).put("topic", q.optString("topic")).put("count", q.optInt("count")).put("date", q.optString("date")))
        }
        return JSONObject().put("quizzes", out).put("max_questions", maxQuestions)
            .put("mode", "online").put("offline_ready", settings.getBool("online_mode"))
    }

    fun quizGet(b: JSONObject): JSONObject {
        val id = b.optString("id").toIntOrNull() ?: throw ApiError(400, "err_bad_request")
        val a = readArr(quizFile)
        for (i in 0 until a.length()) {
            val q = a.getJSONObject(i)
            if (q.getInt("id") == id) {
                val qs = q.optJSONArray("questions")
                if (qs == null || qs.length() == 0) throw ApiError(404, "quiz_corrupt")
                return JSONObject().put("id", id).put("topic", q.optString("topic")).put("questions", qs)
            }
        }
        throw ApiError(404, "quiz_corrupt")
    }

    fun quizDelete(b: JSONObject): JSONObject {
        val id = b.optInt("id", -1)
        val a = readArr(quizFile); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getInt("id") != id) keep.put(a.get(i))
        writeArr(quizFile, keep)
        return JSONObject().put("ok", true)
    }

    fun quizScore(b: JSONObject): JSONObject {
        val qid = b.optInt("quiz_id", -1); val score = b.optInt("score", -1); val total = b.optInt("total", -1)
        if (total < 1 || score < 0 || score > total) throw ApiError(400, "err_bad_request")
        val a = readArr(scoreFile)
        a.put(JSONObject().put("id", nextId(a)).put("quiz_id", qid).put("score", score).put("total", total).put("date", today()))
        writeArr(scoreFile, a)
        return JSONObject().put("ok", true)
    }

    fun quizGenerate(b: JSONObject): JSONObject {
        val topic = b.optString("topic").trim().take(120)
        val count = b.optInt("count", -1)
        val qtype = b.optString("qtype", "mc")
        if (topic.isEmpty() || (qtype != "mc" && qtype != "tf")) throw ApiError(400, "quiz_need_input")
        if (count < 1 || count > maxQuestions) throw ApiError(400, "quiz_count_range")
        val key = requireKey()
        return runJob {
            val lang = if (hasEthiopic(topic)) "Amharic" else "English"
            val rule = if (qtype == "tf") "Each question has exactly two options: [\"True\", \"False\"] and \"answer\" is \"True\" or \"False\"."
            else "Each question has exactly 4 options and \"answer\" is the exact text of the correct option."
            val kind = if (qtype == "tf") "True/False" else "multiple-choice"
            val system = "You are a careful exam-question writer for Ethiopian students. Reply with ONE valid JSON object and nothing else."
            val user = "Write $count $kind quiz questions about: \"$topic\".\nWrite the questions in $lang. $rule\n" +
                "Output JSON only, in this format:\n{\"questions\":[{\"question\":\"...\",\"options\":[\"...\",\"...\"],\"answer\":\"...\",\"explanation\":\"one short sentence\"}]}"
            val tokens = minOf(8192, 350 * count + 400)
            var qs = JSONArray()
            var lastErr: GeminiError? = null
            for (attempt in 0 until 2) {
                try {
                    qs = parseQuiz(ask(key, system, user, tokens, 0.5, true), qtype, count)
                    if (qs.length() > 0) break
                } catch (e: GeminiError) { lastErr = e; if (e.key != "online_busy") throw e }
            }
            if (qs.length() == 0) throw lastErr ?: ApiError(500, "quiz_parse_failed")
            val a = readArr(quizFile)
            val id = nextId(a)
            a.put(JSONObject().put("id", id).put("topic", topic).put("count", qs.length()).put("date", today()).put("questions", qs))
            writeArr(quizFile, a)
            JSONObject().put("quiz_id", id).put("got", qs.length()).put("want", count)
        }
    }

    private fun parseQuiz(raw: String, qtype: String, expected: Int): JSONArray {
        var text = raw.trim()
        Regex("```(?:json)?\\s*(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.let { text = it.groupValues[1].trim() }
        var items = ArrayList<JSONObject>()
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start >= 0) {
            try {
                val t = text.substring(start)
                val arr: JSONArray? = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o ->
                    listOf("questions", "quiz", "items", "data").firstNotNullOfOrNull { o.optJSONArray(it) }
                }
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
            } catch (_: Exception) { }
        }
        val salvaged = ArrayList<JSONObject>()
        for (m in Regex("\\{[^{}]*\\}").findAll(text)) {
            try { val o = JSONObject(m.value); if (o.has("question") || o.has("q")) salvaged.add(o) } catch (_: Exception) { }
        }
        if (salvaged.size > items.size) items = salvaged
        val out = JSONArray(); val seen = HashSet<String>()
        for (it in items) {
            val q = normalise(it, qtype) ?: continue
            if (seen.add(q.getString("question").lowercase())) out.put(q)
            if (out.length() >= expected) break
        }
        return out
    }

    private fun stripLabel(s: String) = s.replace(Regex("^\\s*(?:[A-Fa-f]|\\d{1,2})\\s*[\\)\\.:\\-]\\s+"), "").trim()

    private fun normalise(it: JSONObject, qtype: String): JSONObject? {
        val question = (it.optString("question").ifEmpty { it.optString("q") }).trim()
        if (question.isEmpty()) return null
        val answer = it.opt("answer") ?: it.opt("correct") ?: it.opt("correct_answer")
        val options = ArrayList<String>(); val idx: Int?
        if (qtype == "tf") {
            options.add(msg("tf_true")); options.add(msg("tf_false"))
            val w = answer?.toString()?.trim()?.lowercase()?.replace(Regex("\\s*\\(.*\\)$"), "")
            idx = when (w) { "true", "t", "yes", "y", "correct", "እውነት", "1" -> 0; "false", "f", "no", "n", "incorrect", "ሐሰት", "ሀሰት", "ሃሰት", "0" -> 1; else -> null }
        } else {
            val raw = it.optJSONArray("options") ?: it.optJSONArray("choices") ?: it.optJSONArray("answers") ?: return null
            val seen = HashSet<String>()
            for (i in 0 until raw.length()) {
                val o = raw.opt(i)
                if (o == null || o is JSONObject || o is JSONArray) continue
                val tx = stripLabel(o.toString())
                if (tx.isNotEmpty() && seen.add(tx.lowercase())) options.add(tx)
            }
            if (options.size < 3) return null
            while (options.size > 6) options.removeAt(options.size - 1)
            idx = answerIndex(answer, options)
        }
        if (idx == null) return null
        return JSONObject().put("question", question).put("options", JSONArray(options)).put("answer", options[idx])
            .put("answer_index", idx).put("explanation", it.optString("explanation").trim())
    }

    private fun answerIndex(answer: Any?, options: List<String>): Int? {
        if (answer == null || answer is Boolean) return null
        if (answer is Int) return if (answer in options.indices) answer else null
        val s = answer.toString().trim()
        Regex("\\(?([A-Fa-f])\\)?[\\.\\)]?").matchEntire(s)?.let {
            val i = it.groupValues[1].uppercase()[0] - 'A'
            return if (i < options.size) i else null
        }
        val wanted = stripLabel(s).lowercase()
        options.indexOfFirst { it.lowercase() == wanted }.let { if (it >= 0) return it }
        val hits = options.indices.filter { wanted.isNotEmpty() && (wanted in options[it].lowercase() || options[it].lowercase() in wanted) }
        return if (hits.size == 1) hits[0] else null
    }

    fun notesList(adsStatus: JSONObject): JSONObject {
        val a = readArr(noteFile); val out = JSONArray()
        for (i in a.length() - 1 downTo 0) {
            val n = a.getJSONObject(i)
            out.put(JSONObject().put("id", n.getInt("id")).put("topic", n.optString("topic"))
                .put("preview", n.optString("body").split(Regex("\\s+")).joinToString(" ").take(90)).put("date", n.optString("date")))
        }
        return JSONObject().put("notes", out).put("model_ready", settings.getBool("online_mode")).put("online", true).put("ads", adsStatus)
    }

    fun noteGet(b: JSONObject): JSONObject {
        val id = b.optString("id").toIntOrNull() ?: throw ApiError(400, "err_bad_request")
        val a = readArr(noteFile)
        for (i in 0 until a.length()) if (a.getJSONObject(i).getInt("id") == id) return a.getJSONObject(i)
        throw ApiError(404, "err_not_found")
    }

    fun noteSave(b: JSONObject): JSONObject {
        val topic = b.optString("topic").trim().take(120); val body = b.optString("body").trim().take(6000)
        if (topic.isEmpty() || body.isEmpty()) throw ApiError(400, "err_bad_request")
        val a = readArr(noteFile); val id = nextId(a)
        a.put(JSONObject().put("id", id).put("topic", topic).put("body", body).put("date", today()))
        writeArr(noteFile, a)
        return JSONObject().put("ok", true).put("id", id)
    }

    fun noteDelete(b: JSONObject): JSONObject {
        val id = b.optInt("id", -1)
        val a = readArr(noteFile); val keep = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getInt("id") != id) keep.put(a.get(i))
        writeArr(noteFile, keep)
        return JSONObject().put("ok", true)
    }

    fun noteGenerate(b: JSONObject): JSONObject {
        val topic = b.optString("topic").trim().take(120)
        val length = b.optString("length", "short")
        if (topic.isEmpty() || (length != "short" && length != "medium")) throw ApiError(400, "notes_need_topic")
        val key = requireKey()
        return runJob {
            val lang = if (hasEthiopic(topic)) "Amharic" else "English"
            val words = if (length == "medium") 180 else 110
            val system = "You write clear, accurate, concise study notes for Ethiopian students. Plain text only: no markdown headings, no tables."
            val user = "Write short study notes about: \"$topic\".\nLanguage: $lang. About $words words.\n" +
                "Format: first line is a short title. Then 5 to 8 bullet lines, each starting with '- '. Finish with one line starting with 'Key idea: '."
            val tokens = minOf(8192, (if (length == "medium") 1200 else 800) * (if (lang == "Amharic") 3 else 2))
            val text = cleanNote(ask(key, system, user, tokens, 0.4, false))
            if (text.isEmpty()) throw ApiError(500, "ai_empty")
            JSONObject().put("text", text).put("topic", topic)
        }
    }

    private fun cleanNote(raw: String): String {
        val t = raw.replace("```", "").replace("**", "").replace("__", "")
        return t.lines().map { it.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "").trimEnd().replace(Regex("^\\s*[\\*\\u2022]\\s+"), "- ") }.joinToString("\n").trim()
    }

    fun progress(): JSONObject {
        val scores = readArr(scoreFile); val quizzes = readArr(quizFile)
        val topicOf = HashMap<Int, String>()
        for (i in 0 until quizzes.length()) quizzes.getJSONObject(i).let { topicOf[it.getInt("id")] = it.optString("topic") }
        val out = JSONObject().put("attempts", 0).put("average", 0).put("best", 0).put("streak", 0)
            .put("quizzes", quizzes.length()).put("notes", readArr(noteFile).length())
            .put("recent", JSONArray()).put("topics", JSONArray())
        if (scores.length() == 0) return out
        class Row(val topic: String, val score: Int, val total: Int, val pct: Int, val date: String)
        val rows = ArrayList<Row>()
        for (i in scores.length() - 1 downTo 0) {
            val s = scores.getJSONObject(i)
            val total = s.optInt("total"); if (total < 1) continue
            rows.add(Row(topicOf[s.optInt("quiz_id")] ?: "", s.optInt("score"), total, Math.round(100.0 * s.optInt("score") / total).toInt(), s.optString("date")))
        }
        if (rows.isEmpty()) return out
        out.put("attempts", rows.size).put("average", Math.round(rows.sumOf { it.pct }.toDouble() / rows.size).toInt()).put("best", rows.maxOf { it.pct })
        val recent = JSONArray()
        for (r in rows.take(10)) recent.put(JSONObject().put("topic", r.topic).put("score", r.score).put("total", r.total).put("pct", r.pct).put("date", r.date))
        out.put("recent", recent)
        val per = LinkedHashMap<String, MutableList<Int>>()
        for (r in rows) per.getOrPut(r.topic.ifEmpty { "-" }) { ArrayList() }.add(r.pct)
        val topics = per.entries.sortedWith(compareBy({ -it.value.size }, { it.key })).take(6)
        val ta = JSONArray()
        for (e in topics) ta.put(JSONObject().put("topic", e.key).put("attempts", e.value.size).put("average", Math.round(e.value.sum().toDouble() / e.value.size).toInt()))
        out.put("topics", ta)
        val days = rows.map { it.date }.toSet()
        val cal = Calendar.getInstance()
        if (dayFmt.format(cal.time) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (dayFmt.format(cal.time) in days) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        out.put("streak", n)
        return out
    }

    fun progressReset(): JSONObject { writeArr(scoreFile, JSONArray()); return JSONObject().put("ok", true) }
}
