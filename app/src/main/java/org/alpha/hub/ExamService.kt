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

class ExamService(ctx: Context) {
    private val base = "https://huggingface.co/datasets/Kinfemichael/Ethiopian_Exams_and_Books/resolve/main/"
    private val dir = File(ctx.filesDir, "exams").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val historyFile = File(dir, "history.json")
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun enc(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun fetch(path: String, maxBytes: Int): String {
        val c = URL(base + enc(path)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "AlphaAI/0.1")
            val code = c.responseCode
            if (code == 404) throw ApiError(404, "exam_not_found")
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

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun examFile(id: String) = File(dir, "e_" + safe(id) + ".json")

    private fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp"); tmp.writeText(text, Charsets.UTF_8); tmp.renameTo(f)
    }

    fun index(refresh: Boolean): JSONObject {
        var offline = false
        var text: String? = null
        if (refresh || !indexFile.exists()) {
            try {
                text = fetch("exams_index.json", 4 * 1024 * 1024)
                JSONObject(text)
                write(indexFile, text)
            } catch (e: ApiError) {
                if (!indexFile.exists()) throw e
                offline = true
            } catch (e: Exception) {
                if (!indexFile.exists()) throw ApiError(502, "exam_dl_failed")
                offline = true
            }
        }
        val idx = try { JSONObject(indexFile.readText(Charsets.UTF_8)) } catch (_: Exception) { indexFile.delete(); throw ApiError(502, "exam_dl_failed") }
        val list = idx.optJSONArray("exams") ?: JSONArray()
        for (i in 0 until list.length()) list.getJSONObject(i).put("downloaded", examFile(list.getJSONObject(i).optString("id")).exists())
        return JSONObject().put("exams", list).put("offline", offline)
    }

    fun get(id: String, path: String): JSONObject {
        if (id.isEmpty()) throw ApiError(400, "err_bad_request")
        val f = examFile(id)
        if (f.exists()) try { return JSONObject(f.readText(Charsets.UTF_8)) } catch (_: Exception) { f.delete() }
        if (path.isEmpty() || path.contains("..")) throw ApiError(400, "err_bad_request")
        val text = fetch(path, 8 * 1024 * 1024)
        val j = try { JSONObject(text) } catch (_: Exception) { throw ApiError(502, "exam_dl_failed") }
        if (j.optJSONArray("questions") == null) throw ApiError(502, "exam_dl_failed")
        write(f, text)
        return j
    }

    fun remove(id: String): JSONObject { examFile(id).delete(); return JSONObject().put("ok", true) }

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

    fun reset(): JSONObject { historyFile.delete(); return JSONObject().put("ok", true) }
}
