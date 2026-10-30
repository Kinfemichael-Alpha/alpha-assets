package org.alpha.hub

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * የአፑ ቅንብሮች (settings.json) - ቀድሞ settings_store.py ያደርገው የነበረው ስራ።
 * App settings persisted in filesDir/settings.json (port of settings_store.py).
 */
class SettingsStore(ctx: Context) {
    private val file = File(ctx.filesDir, "settings.json")
    private var data = JSONObject()

    init { load() }

    private fun defaults(): JSONObject = JSONObject()
        .put("theme", "Blue")            // የተስማማ ነባሪ: ሰማያዊ / agreed default: blue
        .put("online_mode", true)
        .put("appearance", "device")   // light | dark | device
        .put("language", "AM")
        .put("nickname", "")
        .put("onboarded", false)
        .put("gemini_key", "")           // የራስ Gemini ቁልፍ (በስልኩ ላይ ብቻ) / own Gemini key, stored locally only
        .put("max_tokens", 2048)         // የመልስ ከፍተኛ ርዝመት / max reply tokens
        .put("temperature", 0.45)        // 0.4-0.5 ነባሪ / default
        .put("resp_len", "long")         // short | long

    @Synchronized
    private fun load() {
        data = defaults()
        try {
            if (file.exists()) {
                val stored = JSONObject(file.readText(Charsets.UTF_8))
                for (k in data.keys().asSequence().toList()) if (stored.has(k)) data.put(k, stored.get(k))
            }
        } catch (_: Exception) { /* የተበላሸ ፋይል -> ነባሪ */ }
        sanitize()
    }

    private fun sanitize() {
        if (data.opt("online_mode") !is Boolean) data.put("online_mode", true)
        if (data.optString("appearance") !in listOf("light", "dark", "device")) data.put("appearance", "device")
        if (data.optString("language") !in listOf("AM", "EN")) data.put("language", "AM")
        data.put("nickname", data.optString("nickname").trim().take(40))
        if (data.opt("onboarded") !is Boolean) data.put("onboarded", false)
        data.put("gemini_key", data.optString("gemini_key").filter { !it.isWhitespace() && !it.isISOControl() }.take(200))
        data.put("max_tokens", data.optInt("max_tokens", 2048).coerceIn(256, 8192))
        data.put("temperature", data.optDouble("temperature", 0.45).let { if (it.isNaN()) 0.45 else it }.coerceIn(0.0, 1.0))
        if (data.optString("resp_len") !in listOf("short", "long")) data.put("resp_len", "long")
    }

    @Synchronized
    private fun save() {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(data.toString(), Charsets.UTF_8)
            tmp.renameTo(file)
        } catch (_: Exception) { }
    }

    @Synchronized fun getString(key: String): String = data.optString(key)
    @Synchronized fun getBool(key: String): Boolean = data.optBoolean(key)
    @Synchronized fun getInt(key: String): Int = data.optInt(key)
    @Synchronized fun getDouble(key: String): Double = data.optDouble(key)

    @Synchronized
    fun set(key: String, value: Any) { data.put(key, value); sanitize(); save() }

    /** ስም፣ onboarding እና ቁልፍ ይጠበቃሉ / nickname, onboarding and key are kept */
    @Synchronized
    fun reset() {
        val nick = data.optString("nickname"); val on = data.optBoolean("onboarded"); val key = data.optString("gemini_key")
        data = defaults(); data.put("nickname", nick); data.put("onboarded", on); data.put("gemini_key", key); save()
    }
}
