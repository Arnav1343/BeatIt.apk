package com.beatit.app

import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start the local HTTP server as a foreground service
        val serviceIntent = Intent(this, MusicServerService::class.java)
        startForegroundService(serviceIntent)

        webView = findViewById(R.id.webView)
        setupWebView()

        // Wait briefly for server to start, then load
        webView.postDelayed({
            webView.loadUrl("http://localhost:8080")
        }, 800)
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
                if (error?.errorCode == ERROR_CONNECT) {
                    // Server not ready yet — retry
                    view?.postDelayed({ view.reload() }, 500)
                }
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, MusicServerService::class.java))
    }
}
