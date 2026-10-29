package org.alpha.hub

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * የ "/api/..." ጥሪዎች ማዘዋወሪያ። ቀድሞ በ Python ሰርቨር (api.py) ይሰራ የነበረው ስራ ወደዚህ ይሸጋገራል።
 * Router for the UI's "/api/..." calls (this replaces the old Python server, endpoint by endpoint).
 *
 * አሁን (v0): ገና ያልተሰሩ ጥሪዎች 501 ከወዳጃዊ መልእክት ጋር ይመልሳሉ፣ በይነገጹ ባዶ ነጭ እንዳይሆን።
 * Now (v0): unimplemented calls return 501 with a friendly message so the UI never ends up blank.
 */
class ApiRouter(@Suppress("unused") private val ctx: Context) {

    fun handle(method: String, path: String, query: String?, headers: Map<String, String>): WebResourceResponse {
        // TODO: እያንዳንዱን endpoint እዚህ ይጨምሩ (session, catalog, download, chat, ...)
        return json(501, """{"ok":false,"message":"ይህ ክፍል ገና አልተጠናቀቀም።"}""")
    }

    private fun json(code: Int, body: String): WebResourceResponse {
        val reason = if (code == 200) "OK" else "Error"
        return WebResourceResponse(
            "application/json", "utf-8", code, reason,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        )
    }
}
