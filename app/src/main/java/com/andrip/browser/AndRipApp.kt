package com.andrip.browser

import android.app.Application
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
        Notifications.createChannel(this)
        appScope.launch {
            try { DownloadQueue.recover(this@AndRipApp) }
            catch (e: Exception) { AppLog.e("Queue", "Could not recover the download queue", e) }
        }
    }
}
