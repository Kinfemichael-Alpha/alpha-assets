package org.alpha.hub

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * ዋናው ማያ ገጽ: HTML/CSS/JS በይነገጹን በ WebView ውስጥ ያሳያል።
 * Main screen: hosts the HTML/CSS/JS UI inside a WebView.
 *
 * ሁለት ዓይነት ጥሪዎች ይጠለፋሉ / two kinds of requests are intercepted:
 *  1) "/api/..."  -> ወደ Kotlin ApiRouter (ቀድሞ Python ሰርቨር ያደርገው የነበረው ስራ)
 *  2) ሌላው        -> ከ assets/web አቃፊ ፋይል ይነበባል (ለምሳሌ /css/style.css -> assets/web/css/style.css)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var router: ApiRouter
    private val pool = Executors.newFixedThreadPool(4)
    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        pool.execute {
            val r = if (uri == null) JSONObject().put("ok", false).put("cancelled", true) else router.prepareImport(uri)
            runOnUiThread { web.evaluateJavascript("window.A && A._picked && A._picked(${r})", null) }
        }
    }
    private val origin = "https://appassets.androidplatform.net"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = ApiRouter(applicationContext)
        router.onExit = { runOnUiThread { finishAffinity() } }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false          // በቀጥታ የፋይል መዳረሻ አይፈቀድም
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host != "appassets.androidplatform.net") return null
                    val path = url.path ?: "/"
                    return serveAsset(if (path == "/") "/index.html" else path)
                }
            }
        }
        // JS -> Kotlin ድልድይ: POST body በ shouldInterceptRequest ስለማይገኝ ጥሪዎች በዚህ ይሄዳሉ
        // JS -> Kotlin bridge (request bodies are not available in shouldInterceptRequest)
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun pickFile() { runOnUiThread { picker.launch(arrayOf("*/*")) } }

            @JavascriptInterface
            fun call(id: Int, method: String, path: String, bodyJson: String) {
                pool.execute {
                    val body = try { JSONObject(bodyJson) } catch (e: Exception) { JSONObject() }
                    val (status, data) = router.handle(method, path, body)
                    val out = JSONObject().put("status", status).put("data", data).toString()
                    // JSON ሕብረቁምፊ ወደ JS እንደ ሕብረቁምፊ ቋሚ ይተላለፋል / pass as a JS string literal
                    val lit = JSONObject.quote(out)
                    runOnUiThread { web.evaluateJavascript("window.A && A._bridgeDone($id, $lit)", null) }
                }
            }
        }, "AlphaBridge")
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        root.addView(web, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bannerBox = android.widget.FrameLayout(this)
        root.addView(bannerBox, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        router.ads = AdManager(this, bannerBox, router.settings)
        web.loadUrl("$origin/index.html")
    }

    /** ከ assets/web ፋይል ያነባል፤ ከሌለ 404 ይመልሳል */
    private fun serveAsset(path: String): WebResourceResponse {
        return try {
            val stream = assets.open("web$path")
            val ext = MimeTypeMap.getFileExtensionFromUrl(path)
            val mime = when (ext) {
                "js" -> "application/javascript"
                "css" -> "text/css"
                "html" -> "text/html"
                "json" -> "application/json"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
            WebResourceResponse(mime, "utf-8", stream)
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    // Back ቁልፍ: በ WebView ታሪክ ውስጥ ወደ ኋላ ይመለሳል
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
