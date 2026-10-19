package com.andrip.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.andrip.browser.browser.AdBlock
import com.andrip.browser.browser.AdBlockPreferences
import com.andrip.browser.browser.BrowserViewModel
import com.andrip.browser.browser.BrowserWebView
import com.andrip.browser.browser.PageProbe
import com.andrip.browser.detect.StreamUrls
import com.andrip.browser.download.DownloadWorker
import com.andrip.browser.download.ExportCheckpoint
import com.andrip.browser.download.SavedFileDeleter
import com.andrip.browser.privacy.BrowsingDataWiper
import com.andrip.browser.privacy.SessionGuardService
import com.andrip.browser.privacy.SessionPrivacy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PlaybackPrivacyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After fun cleanup() {
        AdBlock.load("")
        closeTestDatabase()
    }

    @Test fun passiveProbeDoesNotWrapPlayerRequests() {
        val script = PageProbe.SCRIPT
        assertFalse(script.contains("XMLHttpRequest"))
        assertFalse(script.contains("window.fetch"))
        assertFalse(script.contains(".open ="))
        assertTrue(script.contains("PerformanceObserver"))
        assertTrue(script.contains("AndRipBridge.found"))
        val cosmetic = PageProbe.cosmeticScript(true)
        assertFalse(cosmetic.contains("video"))
        assertFalse(cosmetic.contains("audio"))
        assertTrue(PageProbe.cosmeticScript(false).contains("n.remove()"))
        val webView = source("src/main/java/com/andrip/browser/browser/BrowserWebView.kt")
        assertFalse(webView.contains("XMLHttpRequest"))
        assertFalse(webView.contains("window.fetch"))
    }

    @Test fun streamUrlsAreNeverBlockedEvenOnAdHosts() {
        val domains = setOf("doubleclick.net", "example.com")
        val samples = listOf(
            "https://pubads.g.doubleclick.net/gampad/playlist.m3u8?token=1",
            "https://ads.example.com/master.m3u8",
            "https://ads.example.com/manifest.mpd?hd=1",
            "https://ads.example.com/seg-1.m4s",
            "https://ads.example.com/init.mp4",
            "https://ads.example.com/chunk.ts",
            "https://cdn.example.com/hls/video/1",
            "https://cdn.example.com/file.webm",
            "http://ads.example.com/audio.m4a",
        )
        for (url in samples) {
            assertTrue(url, StreamUrls.mustPassThrough(url))
            assertFalse(url, AdBlock.shouldBlock(url, mainFrame = false, enabled = true, domains = domains))
        }
        assertTrue(AdBlock.shouldBlock("https://pubads.g.doubleclick.net/gampad/ads?sz=1", false, true, domains))
        assertTrue(AdBlock.shouldBlock("https://tracker.example.com/pixel.js", false, true, domains))
        assertFalse(AdBlock.shouldBlock("https://tracker.example.com/pixel.js", mainFrame = true, enabled = true, domains = domains))
        assertFalse(AdBlock.shouldBlock("https://pubads.g.doubleclick.net/gampad/ads?sz=1", false, enabled = false, domains = domains))
        assertFalse(AdBlock.shouldBlock("https://news.example.org/article", false, true, domains))
    }

    @Test fun bundledListBlocksKnownAdHostsAndSkipsPlayerCdns() {
        val domains = AdBlock.parseHosts(source("src/main/assets/adblock_hosts.txt"))
        assertTrue(domains.size > 40)
        assertTrue(AdBlock.listed("pubads.g.doubleclick.net", domains))
        assertTrue(AdBlock.listed("imasdk.googleapis.com", domains))
        assertFalse("google.com" in domains)
        assertFalse("gstatic.com" in domains)
        assertFalse("googlevideo.com" in domains)
        assertFalse("jwplayer.com" in domains)
        assertFalse("jwpcdn.com" in domains)
        assertFalse("akamaihd.net" in domains)
        assertFalse("cloudfront.net" in domains)
        assertFalse(AdBlock.shouldBlock("https://cdn.jwplayer.com/players/a.js", false, true, domains))
        assertFalse(AdBlock.shouldBlock("https://cdn.jwplayer.com/manifests/a.m3u8", false, true, domains))
        assertTrue(AdBlock.shouldBlock("https://securepubads.g.doubleclick.net/gampad/ads", false, true, domains))
    }

    @Test fun webViewLetsManifestsThroughAndOnlyReplacesBlockedTrackers() {
        AdBlock.load("doubleclick.net\n")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = BrowserViewModel(app)
        vm.adBlockEnabled = true
        val view = BrowserWebView.create(app, vm) {}
        try {
            assertNull(intercept(view, "https://pubads.g.doubleclick.net/live/master.m3u8"))
            assertNull(intercept(view, "https://video.example/segment-1.m4s"))
            assertNull(intercept(view, "https://pubads.g.doubleclick.net/gampad/ads.js", mainFrame = true))
            val blocked = intercept(view, "https://pubads.g.doubleclick.net/gampad/ads.js")
            assertEquals(200, blocked?.statusCode)
            assertTrue(blocked!!.data.readBytes().isNotEmpty() || blocked.mimeType == "application/javascript")
            vm.adBlockEnabled = false
            assertNull(intercept(view, "https://pubads.g.doubleclick.net/gampad/ads.js"))
            assertFalse(vm.adBlockActive())
        } finally {
            view.destroy()
        }
    }

    @Test fun adBlockPreferenceSurvivesABrowsingDataWipe() {
        AdBlockPreferences.set(context, false)
        BrowsingDataWiper.wipe(context)
        assertFalse(AdBlockPreferences.enabled(context))
        AdBlockPreferences.set(context, true)
        assertTrue(AdBlockPreferences.enabled(context))
    }

    @Test fun coldStartWipesOnlyAnUncleanSessionAndFullCloseResetsIt() {
        var wipes = 0
        assertFalse(SessionPrivacy.onProcessStart(context) { wipes++ })
        assertEquals(0, wipes)
        SessionPrivacy.onUiSessionStart(context)
        assertFalse(SessionPrivacy.closedCleanly(context))
        assertTrue(SessionPrivacy.onProcessStart(context) { wipes++ })
        assertEquals(1, wipes)
        assertTrue(SessionPrivacy.closedCleanly(context))
        SessionPrivacy.onUiSessionStart(context)
        SessionPrivacy.onFullClose(context) { wipes++ }
        assertEquals(2, wipes)
        assertTrue(SessionPrivacy.closedCleanly(context))
        assertFalse(SessionPrivacy.onProcessStart(context) { wipes++ })
    }

    @Test fun pausingTheActivityDoesNotWipe() {
        val source = source("src/main/java/com/andrip/browser/MainActivity.kt")
        val onPause = source.substringAfter("override fun onPause()").substringBefore("override fun onDestroy()")
        assertFalse(onPause.contains("onFullClose"))
        assertFalse(onPause.contains("BrowsingDataWiper"))
        val onStop = if (source.contains("override fun onStop()")) {
            source.substringAfter("override fun onStop()").substringBefore("override fun ")
        } else ""
        assertFalse(onStop.contains("onFullClose"))
        val onDestroy = source.substringAfter("override fun onDestroy()")
        assertFalse(onDestroy.contains("onFullClose"))
        assertFalse(onDestroy.contains("BrowsingDataWiper"))
        assertFalse(source.contains("OnScroll"))
        assertFalse(source.contains("setOnScrollChangeListener"))
        val ui = source("src/main/java/com/andrip/browser/ui/AndRipScreen.kt")
        assertFalse(ui.contains("barVisible"))
        assertFalse(ui.contains("setOnScrollChangeListener"))
        assertTrue(ui.contains("height(40.dp)"))
        assertTrue(ui.contains("Delete this download?"))
        assertTrue(ui.contains("Ad blocker: on"))
    }

    @Test fun taskRemovalRecordsACleanClose() {
        SessionPrivacy.onUiSessionStart(context)
        val service = Robolectric.buildService(SessionGuardService::class.java).create().get()
        service.onTaskRemoved(Intent())
        assertTrue(SessionPrivacy.closedCleanly(context))
    }

    @Test fun wipeRemovesWebViewDataAndKeepsDownloadsLogsAndPreferences() {
        val download = File(context.noBackupFilesDir, "downloads/9/movie.mp4").apply {
            parentFile?.mkdirs()
            writeText("video-bytes")
        }
        val legacy = SavedFileDeleter.legacyFile(context, "saved.mp4")!!
        legacy.parentFile?.mkdirs()
        legacy.writeText("saved-bytes")
        val log = File(context.filesDir, "logs/andrip.log").apply {
            parentFile?.mkdirs()
            writeText("log")
        }
        val database = File(context.applicationInfo.dataDir, "databases/andrip.db").apply {
            parentFile?.mkdirs()
            writeText("db")
        }
        val cookies = File(context.applicationInfo.dataDir, "app_webview/Default/Cookies").apply {
            parentFile?.mkdirs()
            writeText("cookie=1")
        }
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putBoolean("unmetered", true).commit()
        BrowsingDataWiper.wipe(context)
        assertTrue(download.exists())
        assertEquals("saved-bytes", legacy.readText())
        assertEquals("log", log.readText())
        assertEquals("db", database.readText())
        assertFalse(cookies.exists())
        assertTrue(context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getBoolean("unmetered", false))
    }

    @Test fun deleterRemovesOwnedFilesAndRefusesAnythingElse() {
        val owned = SavedFileDeleter.legacyFile(context, "clip.mp4")!!
        owned.parentFile?.mkdirs()
        owned.writeText("media")
        File(owned.path + ".pending").writeText("partial")
        SavedFileDeleter.deleteUri(context, owned.toURI().toString())
        assertFalse(owned.exists())
        assertFalse(File(owned.path + ".pending").exists())
        assertNull(SavedFileDeleter.legacyFile(context, "../secret.mp4"))
        assertNull(SavedFileDeleter.legacyFile(context, ".."))
        val outside = File(context.cacheDir, "not-andrip.mp4").apply { writeText("keep") }
        val refused = runCatching { SavedFileDeleter.deleteUri(context, outside.toURI().toString()) }
        assertTrue(refused.exceptionOrNull() is java.io.IOException)
        assertEquals("keep", outside.readText())
    }

    @Test fun journaledLegacyExportIsDeletedWithTheWorkDirectory() {
        val owned = SavedFileDeleter.legacyFile(context, "journal.mp4")!!
        owned.parentFile?.mkdirs()
        owned.writeText("journaled")
        val dir = DownloadWorker.workDir(context, 44L)
        dir.mkdirs()
        File(dir, "partial.ts").writeText("partial")
        ExportCheckpoint.write(ExportCheckpoint(dir).publication, JSONObject().put("legacyName", "journal.mp4"))
        SavedFileDeleter.deleteAll(context, null, dir)
        assertFalse(owned.exists())
        dir.deleteRecursively()
    }

    private fun intercept(view: WebView, url: String, mainFrame: Boolean = false) =
        view.webViewClient.shouldInterceptRequest(view, object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame() = mainFrame
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = mapOf("Origin" to "https://player.example", "Referer" to "https://player.example/watch")
        })

    private fun source(path: String): String {
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        return file.readText()
    }
}
