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
import java.io.ByteArrayInputStream

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
    private val origin = "https://appassets.androidplatform.net"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val router = ApiRouter(applicationContext)

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
                    if (path.startsWith("/api/")) {
                        return router.handle(request.method, path, url.query, request.requestHeaders)
                    }
                    return serveAsset(if (path == "/") "/index.html" else path)
                }
            }
        }
        setContentView(web)
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
