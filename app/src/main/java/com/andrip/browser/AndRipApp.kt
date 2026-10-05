package com.andrip.browser

import android.app.Application
import com.andrip.browser.download.Notifications
import com.andrip.browser.log.AppLog

class AndRipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing, so a crash anywhere after this point lands in the log file.
        AppLog.init(this)
        Notifications.createChannel(this)
    }
}
