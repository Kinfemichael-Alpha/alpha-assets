package org.alpha.hub

import android.content.Context
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

    fun handle(method: String, fullPath: String, body: JSONObject): Pair<Int, JSONObject> {
        val path = fullPath.substringBefore('?')
        return try {
            Pair(200, route(method, path, body))
        } catch (e: ApiError) {
            Pair(e.status, JSONObject().put("error", e.key).put("message", msg(e.key)))
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
            .put("language", settings.getString("language"))
            .put("active_model", JSONObject.NULL)
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
        if (b.has("online_mode")) {
            if (b.opt("online_mode") !is Boolean) throw ApiError(400, "err_bad_request")
            settings.set("online_mode", b.getBoolean("online_mode"))
        }
        if (b.has("language")) {
            val l = b.optString("language")
            if (l != "AM" && l != "EN") throw ApiError(400, "err_bad_request")
            settings.set("language", l)
        }
        return JSONObject().put("settings", settingsView())
    }

    companion object { const val APP_VERSION = "0.1" }
}
