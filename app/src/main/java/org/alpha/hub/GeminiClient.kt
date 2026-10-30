package org.alpha.hub

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

/**
 * የ Gemini ስህተት: key = የ i18n ቁልፍ (ወዳጃዊ መልእክት ለተጠቃሚ)፣ technical ለ log ብቻ።
 * Gemini failure: [key] is an i18n key shown to the user; technical text never reaches the UI.
 */
class GeminiError(val key: String, val technical: String = "") : Exception(key)

/** የአንድ ጥሪ ውጤት / result of one streamed call */
class StreamResult(val finishReason: String, val blocked: Boolean)

/**
 * Gemini REST (streamGenerateContent, SSE) - ቃል በቃል ወዲያው ለማሳየት።
 * ሞዴል ስሞች በኮድ ውስጥ አልተጻፉም: ከ ListModels ይወሰዳሉ (404 ሲመጣ ቀጣዩ ይሞከራል)።
 * Model names are NOT hardcoded: taken from ListModels, falling back to the next on 404.
 */
class GeminiClient {
    private val base = "https://generativelanguage.googleapis.com/v1beta"
    @Volatile private var cachedKey = ""
    @Volatile private var cachedModels: List<String> = emptyList()
    @Volatile private var workingModel = ""

    @Volatile private var conn: HttpURLConnection? = null
    /** ተጠቃሚው "አቁም" ሲል አሁን ያለውን ግንኙነት ይቆርጣል / abort the in-flight request */
    fun abort() { try { conn?.disconnect() } catch (_: Exception) { } }

    /** ለውይይት የሚሆኑ ሞዴሎች በቅድሚያ ቅደም ተከተል / usable chat models, best first */
    private fun models(key: String, force: Boolean = false): List<String> {
        if (!force && key == cachedKey && cachedModels.isNotEmpty()) return cachedModels
        val raw = httpGet("$base/models?pageSize=200", key)
        val arr = JSONObject(raw).optJSONArray("models") ?: JSONArray()
        val out = ArrayList<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val name = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var ok = false
            for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
            if (!ok || !name.startsWith("gemini-")) continue
            if (listOf("embedding", "tts", "image", "live", "audio", "robotics", "computer", "learnlm", "aqa", "vision").any { name.contains(it) }) continue
            val flash = name.contains("flash"); val lite = name.contains("lite")
            val unstable = name.contains("preview") || name.contains("exp")
            val score = when {
                flash && !lite && name.endsWith("-latest") -> 0
                flash && !lite && !unstable -> 1
                flash && lite && !unstable -> 2
                flash && !unstable -> 3
                name.contains("pro") && !unstable -> 4
                else -> 5
            }
            out.add(Pair(score, name))
        }
        // በአንድ ነጥብ ውስጥ አዲሱ (ትልቅ ስም) ይቀድማል / newer names first within a score
        val sorted = out.sortedWith(compareBy<Pair<Int, String>> { it.first }.thenByDescending { it.second }).map { it.second }
        if (sorted.isEmpty()) throw GeminiError("online_model_gone", "no usable model")
        cachedKey = key; cachedModels = sorted
        return sorted
    }

    /**
     * contents: [{role, parts:[{text}]}]
     * onDelta ለእያንዳንዱ ቁራጭ ጽሁፍ ይጠራል / called for every streamed text piece.
     */
    fun stream(
        key: String, system: String, contents: JSONArray, maxTokens: Int, temperature: Double,
        isCancelled: () -> Boolean, onDelta: (String) -> Unit,
    ): StreamResult {
        var list = try { models(key) } catch (e: GeminiError) { throw e } catch (e: IOException) { throw mapIo(e) }
        if (workingModel.isNotEmpty() && workingModel in list) list = listOf(workingModel) + list.filter { it != workingModel }
        var lastErr: GeminiError? = null
        for (model in list.take(4)) {
            if (isCancelled()) return StreamResult("STOPPED", false)
            try {
                val r = streamOne(key, model, system, contents, maxTokens, temperature, isCancelled, onDelta)
                workingModel = model
                return r
            } catch (e: GeminiError) {
                // 404 (ሞዴል ጠፍቷል) ወይም ጭነት (429/503) ከሆነ ቀጣዩን ሞዴል ይሞክራል / try next model on gone or busy
                if (e.key == "online_model_gone" || e.key == "online_busy") { lastErr = e; continue }
                throw e
            }
        }
        // ዝርዝሩ ያረጀ ሊሆን ይችላል: በሚቀጥለው ጊዜ እንደገና ይጠየቃል / refresh the list next time
        cachedModels = emptyList()
        throw lastErr ?: GeminiError("online_model_gone")
    }

    private fun streamOne(
        key: String, model: String, system: String, contents: JSONArray, maxTokens: Int, temperature: Double,
        isCancelled: () -> Boolean, onDelta: (String) -> Unit,
    ): StreamResult {
        fun body(withThinking: Boolean): String {
            val gen = JSONObject().put("maxOutputTokens", maxTokens).put("temperature", temperature)
            if (withThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))   // ማሰቢያ ቶከን እንዳይበላ
            val o = JSONObject().put("contents", contents).put("generationConfig", gen)
            if (system.isNotEmpty()) o.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            return o.toString()
        }
        var withThinking = true
        while (true) {
            val c = URL("$base/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
            conn = c
            try {
                c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("x-goog-api-key", key)   // ቁልፉ በ URL ውስጥ እንዳይገባ በ header / key in header, not URL
                c.outputStream.use { it.write(body(withThinking).toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code != 200) {
                    val err = (c.errorStream ?: c.inputStream).bufferedReader(Charsets.UTF_8).readText()
                    // ሞዴሉ thinkingConfig ካልደገፈ ያለሱ እንደገና ይሞክራል / retry without thinking config if rejected
                    if (code == 400 && withThinking && err.contains("thinking", true)) { withThinking = false; continue }
                    throw httpError(code, err)
                }
                var finish = ""; var blocked = false
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        if (isCancelled()) return StreamResult("STOPPED", false)
                        val line = r.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val js = line.substring(5).trim()
                        if (js.isEmpty() || js == "[DONE]") continue
                        val o = try { JSONObject(js) } catch (_: Exception) { continue }
                        if (o.optJSONObject("promptFeedback")?.optString("blockReason", "")?.isNotEmpty() == true) blocked = true
                        val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: continue
                        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if (p.optBoolean("thought", false)) continue   // የሃሳብ ክፍል አይታይም / hide thought parts
                            val t = p.optString("text", "")
                            if (t.isNotEmpty()) onDelta(t)
                        }
                        val fr = cand.optString("finishReason", "")
                        if (fr.isNotEmpty()) finish = fr
                    }
                }
                return StreamResult(finish, blocked)
            } catch (e: GeminiError) { throw e
            } catch (e: IOException) {
                if (isCancelled()) return StreamResult("STOPPED", false)
                throw mapIo(e)
            } finally { try { c.disconnect() } catch (_: Exception) { } }
        }
    }

    private fun httpGet(url: String, key: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("x-goog-api-key", key)
            val code = c.responseCode
            if (code != 200) throw httpError(code, (c.errorStream ?: c.inputStream).bufferedReader(Charsets.UTF_8).readText())
            return c.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: IOException) { throw mapIo(e) } finally { c.disconnect() }
    }

    private fun mapIo(e: IOException): GeminiError = when (e) {
        is UnknownHostException, is SocketTimeoutException, is java.net.ConnectException -> GeminiError("online_no_internet", e.toString())
        is SSLException -> GeminiError("online_ssl", e.toString())
        else -> GeminiError("online_unreachable", e.toString())
    }

    private fun httpError(code: Int, body: String): GeminiError {
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message", "") ?: "" } catch (_: Exception) { "" }
        val key = when {
            code == 401 || code == 403 -> "online_rejected"
            code == 400 && (msg.contains("API key", true) || msg.contains("api_key", true)) -> "online_rejected"
            code == 404 -> "online_model_gone"
            code == 429 || code == 500 || code == 503 || code == 504 -> "online_busy"
            else -> "online_unreachable"
        }
        return GeminiError(key, "HTTP $code $msg")
    }
}
