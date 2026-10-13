package com.andrip.browser

import android.app.Application
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.andrip.browser.browser.BrowserViewModel
import com.andrip.browser.browser.BrowserWebView
import com.andrip.browser.log.LogFile
import com.andrip.browser.log.Redaction
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PrivacyAndRendererTest {
    @After fun cleanup() { closeTestDatabase() }
    @Test fun rendererDeathIsHandledAndReplacedWithoutClosingDownloads() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = BrowserViewModel(app)
        var replacement: WebView? = null
        val view = BrowserWebView.create(app, vm) { replacement = BrowserWebView.create(app, vm) {} }
        val handled = view.webViewClient.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
        })
        assertTrue(handled)
        assertNotNull(replacement)
        assertTrue(vm.pageError!!.contains("Reload"))
        assertFalse(vm.loading)
        replacement!!.destroy()
    }

    @Test fun crashAndLegacyExportRedactNonUrlSecrets() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(app.cacheDir, "privacy-test").apply { mkdirs() }
        try {
            val log = LogFile(dir)
            log.write("CRASH", "test", "test", IllegalStateException("Authorization: Bearer fake-credential"))
            assertFalse(log.current.readText().contains("fake-credential"))
            log.current.appendText("\nCookie: session=fake-cookie\n{\"access_token\":\"fake-json-token\"}")
            val exported = log.export(File(dir, "export.txt"), "test").readText()
            assertFalse(exported.contains("fake-cookie"))
            assertFalse(exported.contains("fake-json-token"))
            assertFalse(Redaction.clean("server said Bearer fake-token").contains("fake-token"))
        } finally { dir.deleteRecursively() }
    }
}
