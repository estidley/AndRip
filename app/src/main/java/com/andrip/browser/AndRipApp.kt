package com.andrip.browser

import android.app.Application
import com.andrip.browser.browser.AdBlock
import com.andrip.browser.download.Notifications
import com.andrip.browser.download.DownloadQueue
import com.andrip.browser.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AndRipApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash anywhere after this point lands in the log file.
        AppLog.init(this)
        runCatching { AdBlock.load(assets.open("adblock_hosts.txt").bufferedReader().use { it.readText() }) }
            .onFailure { AppLog.e("AdBlock", "Could not load the ad block list. Blocking stays off until it loads.", it) }
        try {
            if (com.andrip.browser.privacy.SessionPrivacy.onProcessStart(this)) {
                AppLog.i("Privacy", "Cleared browsing data left by a session that did not close cleanly")
            }
        } catch (e: Exception) {
            AppLog.e("Privacy", "Could not clear browsing data from the previous session", e)
        }
        Notifications.createChannel(this)
        appScope.launch {
            try { DownloadQueue.recover(this@AndRipApp) }
            catch (e: Exception) { AppLog.e("Queue", "Could not recover the download queue", e) }
        }
    }
}
