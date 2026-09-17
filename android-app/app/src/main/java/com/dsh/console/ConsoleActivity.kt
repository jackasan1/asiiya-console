package com.dsh.console

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        // 用主题背景避免加载时闪白
        b.web.setBackgroundColor(
            if ((resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.parseColor("#0D1016")
            else Color.parseColor("#F5F7FB")
        )

        b.swipe.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent),
            ContextCompat.getColor(this, R.color.accent2)
        )
        b.swipe.setOnRefreshListener { b.web.reload() }

        b.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                b.swipe.isRefreshing = false
                b.progress.visibility = android.view.View.GONE
            }
        }
        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility =
                    if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) b.toolbar.title = title
            }
        }
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
