package com.andrip.browser.privacy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.andrip.browser.log.AppLog

/**
 * Started while the browser is open so a task swipe can clear browsing data.
 * `stopWithTask` is false: Android delivers [onTaskRemoved] instead of killing the service silently.
 * This is not a foreground service and has no notification. If Android has already stopped it,
 * [SessionPrivacy.onProcessStart] still wipes on the next cold start.
 */
class SessionGuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            SessionPrivacy.onFullClose(applicationContext)
            AppLog.i(TAG, "Cleared browsing data because the task was removed")
        } catch (e: Exception) {
            AppLog.e(TAG, "Could not clear browsing data when the task was removed", e)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private companion object {
        const val TAG = "Privacy"
    }
}
