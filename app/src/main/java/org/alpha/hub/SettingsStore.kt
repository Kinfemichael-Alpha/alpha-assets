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

    @Synchronized
    fun set(key: String, value: Any) { data.put(key, value); sanitize(); save() }

    /** ስም እና onboarding ይጠበቃሉ / nickname and onboarding are kept */
    @Synchronized
    fun reset() {
        val nick = data.optString("nickname"); val on = data.optBoolean("onboarded")
        data = defaults(); data.put("nickname", nick); data.put("onboarded", on); save()
    }
}
