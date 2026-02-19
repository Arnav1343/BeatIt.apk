package com.beatit.app

import android.os.Bundle
import android.webkit.*
import android.app.Activity

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var server: BeatItServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the local HTTP server on a background thread
        Thread {
            try {
                server = BeatItServer(this, 8080)
                server?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()

        webView = findViewById(R.id.webView)
        setupWebView()

        // Wait for server to start, then load
        webView.postDelayed({
            webView.loadUrl("http://localhost:8080")
        }, 1200)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                // Server not ready yet — retry after a short delay
                if (request?.isForMainFrame == true) {
                    view?.postDelayed({ view.reload() }, 1000)
                }
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try { server?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
