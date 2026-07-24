package com.jarvis.assistant

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Jarvis ka chat UI — poora Flask+HTML/CSS/JS frontend (jo pehle
 * Render/browser pe chalta tha) ab yahin, isi app ke andar, localhost se
 * load hota hai. JarvisService background me Python/Flask start kar chuka
 * hota hai (ya abhi kar raha hota hai) — isliye pehli load thodi retry
 * karti hai jab tak server 127.0.0.1:5000 par respond na kare.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0

    private val serverUrl = "http://127.0.0.1:5000/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        // Ensure service (aur isliye Python/Flask) chalu hai
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loadingOverlay.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Server abhi taiyaar nahi hua — thodi der baad phir try karo
                retryLoad()
            }
        }

        loadWithRetry()
    }

    private fun loadWithRetry() {
        webView.loadUrl(serverUrl)
    }

    private fun retryLoad() {
        attempts++
        if (attempts > 60) return // ~30s ke baad give up (kabhi nahi hona chahiye)
        handler.postDelayed({ loadWithRetry() }, 500)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
