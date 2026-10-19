package com.andrip.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.andrip.browser.log.AppLog

/** Builds the WebView and wires up the two ways videos get detected. */
object BrowserWebView {
    private const val TAG = "WebView"
    private const val BRIDGE = "AndRipBridge"

    private class Bridge(private val vm: BrowserViewModel) {
        /** Called from the page, on a WebView background thread. */
        @JavascriptInterface
        fun found(url: String?, type: String?) {
            if (url.isNullOrEmpty()) return
            vm.report(url, type?.ifEmpty { null }, null, "page")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, vm: BrowserViewModel, onRendererGone: (WebView) -> Unit): WebView {
        val webView = WebView(context)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Leave the user agent, cache mode and cookie behavior otherwise at WebView defaults
            // so page traffic matches a normal WebView session.
        }
        vm.userAgent = webView.settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(vm), BRIDGE)

        webView.webViewClient = object : WebViewClient() {
            // Background thread. The player's request is never re-fetched here.
            // Returning null keeps WebView's own headers, cookies, Origin and CORS handling.
            // The only substitute response is an empty ad/tracker body, and that path refuses stream URLs.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url?.toString() ?: return null
                try {
                    val headers = request.requestHeaders?.toMap().orEmpty()
                    vm.report(url, null, headers["Referer"] ?: headers["referer"], "network", headers)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Request inspection failed", e)
                }
                return try {
                    if (AdBlock.shouldBlock(url, request.isForMainFrame, vm.adBlockActive())) blockedAdResponse(url) else null
                } catch (e: Exception) {
                    AppLog.w(TAG, "Ad block check failed; letting the request through", e)
                    null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                AppLog.d(TAG, "Ignored non-web link: ${request.url}")
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                vm.onPageStarted(url)
                injectProbe(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                vm.loading = false
                vm.url = url
                vm.title = view.title.orEmpty()
                injectProbe(view)
                syncNavigation(view)
            }

            private fun injectProbe(view: WebView) {
                view.evaluateJavascript(PageProbe.SCRIPT, null)
                view.evaluateJavascript(PageProbe.cosmeticScript(vm.adBlockActive()), null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                vm.url = url
                syncNavigation(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    vm.loading = false
                    vm.pageError = when (error.errorCode) {
                        ERROR_HOST_LOOKUP -> "The site could not be found. Check your connection, VPN or Private DNS."
                        ERROR_CONNECT -> "The site refused the connection. Try again or open it in your browser to check sign-in. Browser sign-in may not carry back into AndRip."
                        else -> "This page could not load. Check your connection and try again."
                    }
                    AppLog.w(TAG, "Page load error ${error.errorCode} (${error.description}) for ${request.url}")
                }
            }

            @androidx.annotation.RequiresApi(26)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppLog.e(TAG, "WebView renderer died (crashed=${detail.didCrash()}) on ${vm.url}")
                (view.parent as? ViewGroup)?.removeView(view)
                view.removeJavascriptInterface(BRIDGE)
                view.destroy()
                vm.onPageStarted(vm.url)
                onRendererGone(view)
                vm.loading = false
                vm.canGoBack = false
                vm.canGoForward = false
                vm.pageError = "The page renderer stopped. Tap Reload to reopen this page. Downloads can continue."
                return true
            }

            private fun syncNavigation(view: WebView) {
                vm.canGoBack = view.canGoBack()
                vm.canGoForward = view.canGoForward()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                vm.progress = newProgress
                if (newProgress >= 100) vm.loading = false
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                vm.title = title.orEmpty()
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Only page errors; logging everything would drown the file.
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    AppLog.d("Console", "Page JavaScript error (line ${message.lineNumber()}); message omitted for privacy")
                }
                return true
            }
        }

        // Links the WebView itself would hand to a download manager (Content-Disposition etc).
        webView.setDownloadListener { url, userAgent, _, mimeType, _ ->
            vm.report(url, mimeType, null, "download", mapOf("User-Agent" to userAgent.orEmpty()))
        }
        return webView
    }
}
