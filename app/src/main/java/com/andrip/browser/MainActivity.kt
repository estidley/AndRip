package com.andrip.browser

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import com.andrip.browser.browser.BrowserViewModel
import com.andrip.browser.browser.BrowserWebView
import com.andrip.browser.log.AppLog
import com.andrip.browser.download.Notifications
import com.andrip.browser.ui.AndRipScreen
import com.andrip.browser.ui.AndRipTheme
import com.andrip.browser.util.UrlInput

class MainActivity : ComponentActivity() {
    private val vm: BrowserViewModel by viewModels()
    private var currentWebView by mutableStateOf<WebView?>(null)
    private val webView: WebView get() = checkNotNull(currentWebView)

    private fun createWebView(): WebView = BrowserWebView.create(this, vm) { dead ->
        if (currentWebView === dead) currentWebView = createWebView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

        currentWebView = createWebView()
        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) webView.loadUrl(externalUrl(intent) ?: UrlInput.HOME)

        setContent {
            AndRipTheme { key(webView) { AndRipScreen(vm, webView) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        externalUrl(intent)?.let {
            AppLog.d("App", "Opened from another app: $it")
            webView.loadUrl(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.OPEN_DOWNLOADS, false) == true) {
            vm.openDownloads = true
            intent.removeExtra(Notifications.OPEN_DOWNLOADS)
        }
    }

    private fun externalUrl(intent: Intent?): String? = intent?.data?.takeIf {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank()
    }?.toString()

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
