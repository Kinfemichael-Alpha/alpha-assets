package org.alpha.hub

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class ExamService(ctx: Context) {
    @Volatile var contentToken: (() -> String?)? = null
    private val base = "https://huggingface.co/datasets/Kinfemichael/Ethiopian_Exams_and_Books/resolve/main/"
    private val dir = File(ctx.filesDir, "exams").apply { mkdirs() }
    private val historyFile = File(dir, "history.json")
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val indexNames = mapOf(
        "past" to listOf("exams_index.json"),
        "generated" to listOf("generates_exams_url.json", "generated_exams_url.json"),
        "notes" to listOf("generated_notes_url.json", "generates_notes_url.json"),
    )
    private val listKeys = listOf("exams", "notes", "files", "items", "sets", "data")

    private fun kindOf(k: String): String = if (k == "generated" || k == "notes") k else "past"
    private fun enc(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun paidRel(path: String): String =
        if (path.startsWith("https://")) path.substringAfter("/resolve/main/", "") else path

    private fun fetch(path: String, maxBytes: Int, paid: Boolean = false): String {
        val tk = if (paid) contentToken?.invoke() else null
        if (tk != null) {
            val rel = paidRel(path)
            if (rel.isNotEmpty() && !rel.contains("..")) {
                try { return open(LicenseManager.SERVER + "/content/" + (if (path.startsWith("https://")) rel else enc(rel)), maxBytes, tk) }
                catch (e: ApiError) { if (e.status != 503) throw e }
            }
        }
        val full = if (path.startsWith("https://")) {
            val host = try { URL(path).host } catch (_: Exception) { "" }
            if (host != "huggingface.co" && !host.endsWith(".huggingface.co")) throw ApiError(400, "err_bad_request")
            path
        } else base + enc(path)
        return open(full, maxBytes, null)
    }

    private fun open(full: String, maxBytes: Int, token: String?): String {
        val c = URL(full).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 90000
            c.setRequestProperty("User-Agent", "AlphaAI/0.1")
            if (token != null) c.setRequestProperty("x-alpha-token", token)
            val code = c.responseCode
            if (code == 404) throw ApiError(404, "exam_not_found")
            if (code == 403 && token != null) throw ApiError(403, "tier_required")
            if (code == 503 && token != null) throw ApiError(503, "content_unconfigured")
            if (code != 200) throw ApiError(502, "exam_dl_failed")
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > maxBytes) throw ApiError(502, "exam_dl_failed")
            return String(bytes, Charsets.UTF_8)
        } catch (e: ApiError) { throw e
        } catch (e: java.net.UnknownHostException) { throw ApiError(503, "online_no_internet")
        } catch (e: java.net.SocketTimeoutException) { throw ApiError(503, "online_no_internet")
        } catch (e: java.net.ConnectException) { throw ApiError(503, "online_no_internet")
        } catch (e: Exception) { throw ApiError(502, "exam_dl_failed")
        } finally { c.disconnect() }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)
    private fun indexFile(kind: String) = File(dir, if (kind == "past") "index.json" else "index_$kind.json")
    private fun examFile(kind: String, id: String) = File(dir, (if (kind == "past") "e_" else if (kind == "generated") "g_" else "n_") + safe(id) + ".json")

    private fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp"); tmp.writeText(text, Charsets.UTF_8); tmp.renameTo(f)
    }

    private fun normalizeIndex(text: String): JSONArray {
        val root: Any = try { JSONTokener(text).nextValue() } catch (_: Exception) { throw ApiError(502, "exam_dl_failed") }
        var arr: JSONArray? = root as? JSONArray
        if (arr == null && root is JSONObject) for (k in listKeys) { arr = root.optJSONArray(k); if (arr != null) break }
        if (arr == null) throw ApiError(502, "exam_dl_failed")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            val e = if (o != null) o else JSONObject().put("url", arr.optString(i))
            val loc = listOf("url", "download_url", "link", "path", "file").map { e.optString(it) }.firstOrNull { it.isNotEmpty() } ?: continue
            val name = loc.substringBefore('?').substringAfterLast('/').removeSuffix(".json")
            val id = e.optString("id").ifEmpty { name }
            val n = JSONObject()
            n.put("id", id).put("path", loc)
            n.put("title", e.optString("title").ifEmpty { e.optString("name").ifEmpty { name.replace('_', ' ').replace('-', ' ') } })
            n.put("grade", e.opt("grade") ?: JSONObject.NULL).put("subject", e.optString("subject").ifEmpty { "" })
            n.put("count", e.optInt("count", e.optInt("questions", 0)))
            n.put("year_ec", e.opt("year_ec") ?: JSONObject.NULL).put("round", e.opt("round") ?: JSONObject.NULL)
            n.put("topics", e.optJSONObject("topics") ?: JSONObject())
            out.put(n)
        }
        return out
    }

    fun index(kindIn: String, refresh: Boolean): JSONObject {
        val kind = kindOf(kindIn)
        val f = indexFile(kind)
        var offline = false
        if (refresh || !f.exists()) {
            var lastErr: ApiError? = null
            var ok = false
            for (name in indexNames.getValue(kind)) {
                try {
                    val text = fetch(name, 4 * 1024 * 1024, kind != "past")
                    normalizeIndex(text)
                    write(f, text)
                    ok = true
                    break
                } catch (e: ApiError) { lastErr = e; if (e.status != 404) break
                } catch (e: Exception) { lastErr = ApiError(502, "exam_dl_failed"); break }
            }
            if (!ok) {
                if (!f.exists()) { if (lastErr?.status == 404) return JSONObject().put("exams", JSONArray()).put("offline", false); throw lastErr ?: ApiError(502, "exam_dl_failed") }
                offline = true
            }
        }
        val list = try { normalizeIndex(f.readText(Charsets.UTF_8)) } catch (_: Exception) { f.delete(); throw ApiError(502, "exam_dl_failed") }
        for (i in 0 until list.length()) list.getJSONObject(i).put("downloaded", examFile(kind, list.getJSONObject(i).optString("id")).exists())
        return JSONObject().put("exams", list).put("offline", offline)
    }

    fun get(kindIn: String, id: String, path: String): JSONObject {
        val kind = kindOf(kindIn)
        if (id.isEmpty()) throw ApiError(400, "err_bad_request")
        val f = examFile(kind, id)
        if (f.exists()) try { return wrap(kind, JSONTokener(f.readText(Charsets.UTF_8)).nextValue()) } catch (_: Exception) { f.delete() }
        if (path.isEmpty() || path.contains("..")) throw ApiError(400, "err_bad_request")
        val text = fetch(path, 25 * 1024 * 1024, kind != "past")
        val j = try { wrap(kind, JSONTokener(text).nextValue()) } catch (e: ApiError) { throw e } catch (_: Exception) { throw ApiError(502, "exam_dl_failed") }
        write(f, text)
        return j
    }

    private fun wrap(kind: String, v: Any?): JSONObject {
        if (kind == "notes") {
            if (v is JSONArray) return JSONObject().put("notes", v)
            if (v is JSONObject) return v
            throw ApiError(502, "exam_dl_failed")
        }
        val j = if (v is JSONArray) JSONObject().put("questions", v) else v as? JSONObject ?: throw ApiError(502, "exam_dl_failed")
        if (j.optJSONArray("questions") == null) throw ApiError(502, "exam_dl_failed")
        return j
    }

    fun isCached(kindIn: String, id: String): Boolean = id.isNotEmpty() && examFile(kindOf(kindIn), id).exists()

    fun remove(kindIn: String, id: String): JSONObject { examFile(kindOf(kindIn), id).delete(); return JSONObject().put("ok", true) }

    private fun history(): JSONArray = try { if (historyFile.exists()) JSONArray(historyFile.readText(Charsets.UTF_8)) else JSONArray() } catch (_: Exception) { JSONArray() }

    fun attempt(b: JSONObject): JSONObject {
        val total = b.optInt("total"); if (total < 1) throw ApiError(400, "err_bad_request")
        val h = history()
        val a = JSONObject()
            .put("date", dayFmt.format(Date()))
            .put("subject", b.optString("subject")).put("label", b.optString("label"))
            .put("score", b.optInt("score")).put("total", total).put("seconds", b.optInt("seconds"))
            .put("topics", b.optJSONObject("topics") ?: JSONObject())
            .put("wrong", b.optJSONArray("wrong") ?: JSONArray())
        h.put(a)
        val keep = JSONArray()
        for (i in maxOf(0, h.length() - 300) until h.length()) keep.put(h.get(i))
        write(historyFile, keep.toString())
        return JSONObject().put("ok", true)
    }

    fun stats(): JSONObject {
        val h = history()
        val per = LinkedHashMap<String, IntArray>()
        val recent = JSONArray()
        for (i in h.length() - 1 downTo 0) {
            val a = h.getJSONObject(i)
            if (recent.length() < 10) recent.put(JSONObject().put("label", a.optString("label")).put("subject", a.optString("subject"))
                .put("score", a.optInt("score")).put("total", a.optInt("total")).put("date", a.optString("date")))
            val tp = a.optJSONObject("topics") ?: continue
            val sub = a.optString("subject")
            for (k in tp.keys()) {
                val v = tp.optJSONArray(k) ?: continue
                val r = per.getOrPut("$sub|$k") { IntArray(2) }
                r[0] += v.optInt(0); r[1] += v.optInt(1)
            }
        }
        val weak = JSONArray()
        per.entries.filter { it.value[1] >= 3 }.sortedBy { it.value[0].toDouble() / it.value[1] }.take(8).forEach {
            weak.put(JSONObject().put("subject", it.key.substringBefore('|')).put("topic", it.key.substringAfter('|'))
                .put("correct", it.value[0]).put("total", it.value[1]))
        }
        return JSONObject().put("attempts", h.length()).put("recent", recent).put("weak", weak)
    }

    private val progressFile = File(dir, "progress.json")

    fun saveProgress(b: JSONObject): JSONObject {
        if ((b.optJSONArray("order")?.length() ?: 0) < 1) throw ApiError(400, "err_bad_request")
        write(progressFile, b.toString())
        return JSONObject().put("ok", true)
    }

    fun progress(): JSONObject {
        val st = try { if (progressFile.exists()) JSONObject(progressFile.readText(Charsets.UTF_8)) else null } catch (_: Exception) { progressFile.delete(); null }
        return if (st == null) JSONObject().put("has", false) else JSONObject().put("has", true).put("state", st)
    }

    fun clearProgress(): JSONObject { progressFile.delete(); return JSONObject().put("ok", true) }

    fun reset(): JSONObject { historyFile.delete(); return JSONObject().put("ok", true) }
}
