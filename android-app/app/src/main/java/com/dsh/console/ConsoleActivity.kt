package com.dsh.console

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.dsh.console.databinding.ActivityConsoleBinding

/** 内嵌 dsh Web UI */
class ConsoleActivity : AppCompatActivity() {

    companion object { const val EXTRA_URL = "extra_url" }

    private lateinit var b: ActivityConsoleBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        b.toolbar.setNavigationOnClickListener { finish() }
        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        b.web.webViewClient = WebViewClient()
        b.web.webChromeClient = WebChromeClient()
        if (url.isNotEmpty()) b.web.loadUrl(url)

        b.btnReload.setOnClickListener { b.web.reload() }
        b.btnExternal.setOnClickListener {
            val u = b.web.url ?: url
            if (u.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::b.isInitialized && b.web.canGoBack()) b.web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (this::b.isInitialized) {
            b.web.stopLoading()
            b.web.destroy()
        }
        super.onDestroy()
    }
}
