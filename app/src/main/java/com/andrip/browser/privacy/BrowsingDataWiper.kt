package com.andrip.browser.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.io.File

/**
 * Clears WebView browsing data only: cookies, cache, web storage, history and form data.
 * Downloads, the queue database, logs and app preferences (including the ad blocker) are left in place.
 */
object BrowsingDataWiper {
    fun wipe(context: Context) {
        val app = context.applicationContext
        deleteWebViewDirectories(app)
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            val view = WebView(app)
            view.clearCache(true)
            view.clearHistory()
            @Suppress("DEPRECATION")
            view.clearFormData()
            view.clearSslPreferences()
            view.destroy()
        }
        runCatching {
            val database = WebViewDatabase.getInstance(app)
            @Suppress("DEPRECATION")
            database.clearFormData()
            @Suppress("DEPRECATION")
            database.clearHttpAuthUsernamePassword()
        }
        // APIs above can recreate an empty WebView profile. Remove that too.
        deleteWebViewDirectories(app)
    }

    private fun deleteWebViewDirectories(context: Context) {
        val data = context.applicationInfo.dataDir?.let { File(it).canonicalFile } ?: return
        for (name in listOf("app_webview", "app_webview_cache", "app_textures")) {
            deleteChild(data, name)
        }
        val cache = context.cacheDir?.canonicalFile ?: return
        for (name in listOf("WebView", "org.chromium.android_webview")) {
            deleteChild(cache, name)
        }
    }

    private fun deleteChild(parent: File, name: String) {
        val child = File(parent, name).canonicalFile
        if (child.parentFile == parent) child.deleteRecursively()
    }
}
