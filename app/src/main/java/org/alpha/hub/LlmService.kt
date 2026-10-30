package org.alpha.hub

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONObject

class LlmService(ctx: Context, private val settings: SettingsStore) {
    private val gemini = GeminiClient()
    private val wire = WireClient()
    private val presets: JSONArray = JSONArray(ctx.assets.open("providers.json").bufferedReader(Charsets.UTF_8).readText())
    private val modelCache = HashMap<String, String>()

    private fun preset(id: String): JSONObject? {
        for (i in 0 until presets.length()) if (presets.getJSONObject(i).optString("id") == id) return presets.getJSONObject(i)
        return null
    }

    fun activeId(): String = settings.getString("provider").let { if (preset(it) != null) it else "gemini" }

    private fun cfg(id: String): JSONObject = settings.getJson("provider_cfg").optJSONObject(id) ?: JSONObject()
    private fun keyOf(id: String) = if (id == "gemini") settings.getString("gemini_key") else cfg(id).optString("key")
    private fun baseOf(id: String): String {
        val p = preset(id) ?: return ""
        return cfg(id).optString("base_url").ifEmpty { p.optString("base_url") }.trim().trimEnd('/')
    }
    private fun modelOf(id: String) = cfg(id).optString("model")

    fun configured(): Boolean {
        val id = activeId()
        return keyOf(id).isNotEmpty() && (id == "gemini" || baseOf(id).startsWith("https://"))
    }

    fun abort() { gemini.abort(); wire.abort() }

    fun providers(): JSONArray {
        val a = JSONArray(); val act = activeId()
        for (i in 0 until presets.length()) {
            val p = presets.getJSONObject(i); val id = p.getString("id"); val k = keyOf(id)
            a.put(JSONObject().put("id", id).put("name", p.optString("name")).put("type", p.optString("type"))
                .put("key_set", k.isNotEmpty()).put("key_tail", k.takeLast(4)).put("base_url", baseOf(id))
                .put("editable_url", p.optBoolean("editable_url", false)).put("model", modelOf(id))
                .put("hint", p.optString("hint")).put("active", id == act))
        }
        return a
    }

    fun select(id: String) { if (preset(id) == null) throw ApiError(400, "err_bad_request"); settings.set("provider", id) }

    fun save(b: JSONObject) {
        val id = b.optString("id"); val p = preset(id) ?: throw ApiError(400, "err_bad_request")
        if (id == "gemini") {
            if (b.has("key")) settings.set("gemini_key", b.optString("key").filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(200))
            return
        }
        val all = settings.getJson("provider_cfg")
        val c = all.optJSONObject(id) ?: JSONObject()
        if (b.has("key")) c.put("key", b.optString("key").filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(300))
        if (b.has("model")) c.put("model", b.optString("model").trim().take(120))
        if (b.has("base_url") && p.optBoolean("editable_url", false)) {
            val u = b.optString("base_url").trim().trimEnd('/').take(200)
            if (u.isNotEmpty() && !u.startsWith("https://")) throw ApiError(400, "err_bad_request")
            c.put("base_url", u)
        }
        all.put(id, c)
        settings.set("provider_cfg", all)
        modelCache.remove(id)
    }

    fun listModels(id: String): List<String> {
        val p = preset(id) ?: throw ApiError(400, "err_bad_request")
        val key = keyOf(id)
        if (key.isEmpty()) throw ApiError(400, "online_need_key")
        return when (p.optString("type")) {
            "gemini" -> listOf(gemini.probe(key))
            "anthropic" -> wire.listModels(baseOf(id), key, true)
            else -> wire.listModels(baseOf(id), key, false)
        }
    }

    fun probe(): String {
        val id = activeId()
        if (!configured()) throw GeminiError("online_need_key")
        return if (id == "gemini") gemini.probe(keyOf(id)) else listModels(id).let { it.firstOrNull() ?: throw GeminiError("online_model_gone") }
    }

    private fun resolveModel(id: String, anthropic: Boolean): String {
        val m = modelOf(id)
        if (m.isNotEmpty()) return m
        modelCache[id]?.let { return it }
        val list = wire.listModels(baseOf(id), keyOf(id), anthropic)
        val pick = list.firstOrNull() ?: throw GeminiError("online_model_gone", "empty model list")
        modelCache[id] = pick
        return pick
    }

    fun stream(
        system: String, contents: JSONArray, maxTokens: Int, temperature: Double, json: Boolean,
        isCancelled: () -> Boolean, onDelta: (String) -> Unit,
    ): StreamResult {
        val id = activeId(); val key = keyOf(id)
        if (key.isEmpty()) throw GeminiError("online_need_key")
        val type = preset(id)?.optString("type") ?: "gemini"
        if (type == "gemini") return gemini.stream(key, system, contents, maxTokens, temperature, json, isCancelled, onDelta)
        val turns = ArrayList<Pair<String, String>>()
        for (i in 0 until contents.length()) {
            val c = contents.getJSONObject(i)
            val role = if (c.optString("role") == "model") "assistant" else "user"
            val text = c.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: ""
            if (text.isEmpty()) continue
            if (turns.isNotEmpty() && turns.last().first == role) turns[turns.size - 1] = Pair(role, turns.last().second + "\n" + text) else turns.add(Pair(role, text))
        }
        val base = baseOf(id)
        val anth = type == "anthropic"
        val model = resolveModel(id, anth)
        return try {
            if (anth) wire.streamAnthropic(base, key, model, system, turns, maxTokens, temperature, isCancelled, onDelta)
            else wire.streamOpenAi(base, key, model, system, turns, maxTokens, temperature, json, isCancelled, onDelta)
        } catch (e: GeminiError) { if (e.key == "online_model_gone") modelCache.remove(id); throw e }
    }
}

class WireClient {
    @Volatile private var conn: HttpURLConnection? = null
    fun abort() { try { conn?.disconnect() } catch (_: Exception) { } }

    private fun mapIo(e: IOException): GeminiError = when (e) {
        is UnknownHostException, is SocketTimeoutException, is java.net.ConnectException -> GeminiError("online_no_internet", e.toString())
        is SSLException -> GeminiError("online_ssl", e.toString())
        else -> GeminiError("online_unreachable", e.toString())
    }

    private fun httpError(code: Int, body: String): GeminiError {
        val msg = try { JSONObject(body).let { o -> o.optJSONObject("error")?.optString("message") ?: o.optString("message") } } catch (_: Exception) { body.take(200) }
        val low = msg.lowercase()
        val key = when {
            code == 401 || code == 403 -> "online_rejected"
            code == 402 || low.contains("quota") || low.contains("insufficient") || low.contains("credit") || low.contains("billing") -> "online_quota"
            code == 404 || (code == 400 && low.contains("model")) -> "online_model_gone"
            code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 529 -> "online_busy"
            else -> "online_unreachable"
        }
        return GeminiError(key, "HTTP $code $msg")
    }

    private fun open(url: String, key: String, anth: Boolean, post: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = if (post) 60000 else 20000
        if (anth) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01") }
        else c.setRequestProperty("Authorization", "Bearer $key")
        if (post) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        return c
    }

    fun listModels(base: String, key: String, anth: Boolean): List<String> {
        val url = if (anth) "$base/v1/models?limit=100" else "$base/models"
        val c = open(url, key, anth, false)
        try {
            if (c.responseCode != 200) throw httpError(c.responseCode, (c.errorStream ?: c.inputStream).bufferedReader(Charsets.UTF_8).readText())
            val o = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val arr = o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
            val skip = listOf("embed", "whisper", "tts", "audio", "image", "moderation", "dall", "vision-preview", "transcribe", "realtime", "rerank", "guard")
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id")?.ifEmpty { null } ?: arr.optString(i)
                if (id.isNotEmpty() && skip.none { id.lowercase().contains(it) }) out.add(id)
            }
            return out
        } catch (e: IOException) { throw mapIo(e) } finally { c.disconnect() }
    }

    fun streamOpenAi(
        base: String, key: String, model: String, system: String, turns: List<Pair<String, String>>, maxTokens: Int,
        temperature: Double, json: Boolean, isCancelled: () -> Boolean, onDelta: (String) -> Unit,
    ): StreamResult {
        var tokenParam = "max_tokens"; var useTemp = true; var useJson = json
        for (attempt in 0 until 4) {
            val msgs = JSONArray()
            if (system.isNotEmpty()) msgs.put(JSONObject().put("role", "system").put("content", system))
            for ((r, t) in turns) msgs.put(JSONObject().put("role", r).put("content", t))
            val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true).put(tokenParam, maxTokens)
            if (useTemp) body.put("temperature", temperature)
            if (useJson) body.put("response_format", JSONObject().put("type", "json_object"))
            val c = open("$base/chat/completions", key, false, true)
            conn = c
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code != 200) {
                    val err = (c.errorStream ?: c.inputStream).bufferedReader(Charsets.UTF_8).readText()
                    val low = err.lowercase()
                    if (code == 400 && tokenParam == "max_tokens" && low.contains("max_completion_tokens")) { tokenParam = "max_completion_tokens"; continue }
                    if (code == 400 && useTemp && low.contains("temperature")) { useTemp = false; continue }
                    if (code == 400 && useJson && (low.contains("response_format") || low.contains("json"))) { useJson = false; continue }
                    throw httpError(code, err)
                }
                var finish = ""
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        if (isCancelled()) return StreamResult("STOPPED", false)
                        val line = r.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val js = line.substring(5).trim()
                        if (js.isEmpty() || js == "[DONE]") continue
                        val o = try { JSONObject(js) } catch (_: Exception) { continue }
                        val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        val t = ch.optJSONObject("delta")?.optString("content", "") ?: ""
                        if (t.isNotEmpty() && t != "null") onDelta(t)
                        val fr = ch.optString("finish_reason", "")
                        if (fr.isNotEmpty() && fr != "null") finish = fr
                    }
                }
                return StreamResult(if (finish == "length") "MAX_TOKENS" else if (finish == "content_filter") "SAFETY" else finish.uppercase(), false)
            } catch (e: GeminiError) { throw e
            } catch (e: IOException) {
                if (isCancelled()) return StreamResult("STOPPED", false)
                throw mapIo(e)
            } finally { try { c.disconnect() } catch (_: Exception) { } }
        }
        throw GeminiError("online_unreachable", "retries exhausted")
    }

    fun streamAnthropic(
        base: String, key: String, model: String, system: String, turns: List<Pair<String, String>>, maxTokens: Int,
        temperature: Double, isCancelled: () -> Boolean, onDelta: (String) -> Unit,
    ): StreamResult {
        val msgs = JSONArray()
        for ((r, t) in turns) msgs.put(JSONObject().put("role", r).put("content", t))
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", msgs).put("stream", true)
            .put("temperature", temperature.coerceIn(0.0, 1.0))
        if (system.isNotEmpty()) body.put("system", system)
        val c = open("$base/v1/messages", key, true, true)
        conn = c
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) throw httpError(code, (c.errorStream ?: c.inputStream).bufferedReader(Charsets.UTF_8).readText())
            var finish = ""
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    if (isCancelled()) return StreamResult("STOPPED", false)
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val o = try { JSONObject(line.substring(5).trim()) } catch (_: Exception) { continue }
                    when (o.optString("type")) {
                        "content_block_delta" -> o.optJSONObject("delta")?.let { d -> if (d.optString("type") == "text_delta") d.optString("text").let { if (it.isNotEmpty()) onDelta(it) } }
                        "message_delta" -> o.optJSONObject("delta")?.optString("stop_reason", "")?.let { if (it.isNotEmpty() && it != "null") finish = it }
                        "error" -> throw GeminiError("online_busy", o.toString().take(200))
                    }
                }
            }
            return StreamResult(if (finish == "max_tokens") "MAX_TOKENS" else if (finish == "refusal") "SAFETY" else finish.uppercase(), false)
        } catch (e: GeminiError) { throw e
        } catch (e: IOException) {
            if (isCancelled()) return StreamResult("STOPPED", false)
            throw mapIo(e)
        } finally { try { c.disconnect() } catch (_: Exception) { } }
    }
}
