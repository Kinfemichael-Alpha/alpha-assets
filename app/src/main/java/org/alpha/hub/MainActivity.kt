package org.alpha.hub

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

/**
 * ዋናው ማያ ገጽ: HTML/CSS/JS በይነገጽን በ WebView ውስጥ ያሳያል።
 * Main screen: hosts the HTML/CSS/JS UI inside a WebView.
 *
 * በይነገጹ ከ assets/web በ https://appassets.androidplatform.net/ በኩል ይጫናል (ደህንነቱ የተጠበቀ ምናባዊ አድራሻ)።
 * The UI loads from assets/web through a virtual https origin (no file:// access needed).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false          // በቀጥታ የፋይል መዳረሻ አይፈቀድም
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    loader.shouldInterceptRequest(request.url)
            }
        }
        setContentView(web)
        web.loadUrl("https://appassets.androidplatform.net/web/index.html")
    }

    // Back ቁልፍ: በ WebView ታሪክ ውስጥ ወደ ኋላ ይመለሳል
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
