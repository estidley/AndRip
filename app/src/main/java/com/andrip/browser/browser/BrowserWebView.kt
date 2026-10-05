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

    /**
     * Runs inside each page. WebView's request interceptor never sees <video src> that was
     * already cached, or anything fetched by a service worker, so the page reports those itself.
     * It only ever passes URLs to the bridge; classification happens in Kotlin.
     */
    private val HOOK_JS = """
        (function () {
          if (window.__andripHooked) return;
          window.__andripHooked = true;
          function report(u, type) {
            try {
              if (!u || typeof u !== 'string') return;
              if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
              $BRIDGE.found(new URL(u, location.href).href, type || '');
            } catch (e) {}
          }
          function scan() {
            try {
              var nodes = document.querySelectorAll('video, video source');
              for (var i = 0; i < nodes.length; i++) {
                report(nodes[i].currentSrc || nodes[i].src, nodes[i].type || '');
              }
            } catch (e) {}
          }
          var timer = null;
          function scanSoon() {
            if (timer) return;
            timer = setTimeout(function () { timer = null; scan(); }, 500);
          }
          var realFetch = window.fetch;
          if (realFetch) {
            window.fetch = function (input) {
              try { report(typeof input === 'string' ? input : (input && input.url), ''); } catch (e) {}
              return realFetch.apply(this, arguments);
            };
          }
          var realOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, u) {
            try { report(String(u), ''); } catch (e) {}
            return realOpen.apply(this, arguments);
          };
          try {
            if (document.documentElement) {
              new MutationObserver(scanSoon).observe(document.documentElement, { childList: true, subtree: true });
            }
          } catch (e) {}
          document.addEventListener('play', scanSoon, true);
          document.addEventListener('loadedmetadata', scanSoon, true);
          scan();
        })();
    """.trimIndent()

    private class Bridge(private val vm: BrowserViewModel) {
        /** Called from the page, on a WebView background thread. */
        @JavascriptInterface
        fun found(url: String?, type: String?) {
            if (url.isNullOrEmpty()) return
            vm.report(url, type?.ifEmpty { null }, null, "page")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, vm: BrowserViewModel): WebView {
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
        }
        vm.userAgent = webView.settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(Bridge(vm), BRIDGE)

        webView.webViewClient = object : WebViewClient() {
            // Background thread. Returning null lets the request go through untouched.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                try {
                    val headers = request.requestHeaders
                    vm.report(request.url.toString(), null, headers?.get("Referer") ?: headers?.get("referer"), "network")
                } catch (e: Exception) {
                    AppLog.w(TAG, "Request inspection failed", e)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                AppLog.d(TAG, "Ignored non-web link: ${request.url}")
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                vm.onPageStarted(url)
                view.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                vm.loading = false
                vm.url = url
                vm.title = view.title.orEmpty()
                view.evaluateJavascript(HOOK_JS, null)
                syncNavigation(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                vm.url = url
                syncNavigation(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    AppLog.w(TAG, "Page load error ${error.errorCode} (${error.description}) for ${request.url}")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppLog.e(TAG, "WebView renderer died (crashed=${detail.didCrash()}) on ${vm.url}")
                return false
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
                    AppLog.d("Console", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                }
                return true
            }
        }

        // Links the WebView itself would hand to a download manager (Content-Disposition etc).
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            vm.report(url, mimeType, null, "download")
        }
        return webView
    }
}
