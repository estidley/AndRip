package com.andrip.browser.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andrip.browser.MainActivity
import com.andrip.browser.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object Notifications {
    private const val CHANNEL = "downloads"
    const val OPEN_DOWNLOADS = "open_downloads"

    fun enabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < 26) return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"))
        val appEnabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        return Intent(if (appEnabled) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
            else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
    }

    private fun progressText(percent: Int, detail: String): String = when {
        percent >= 0 && detail.isNotBlank() -> "${percent.coerceIn(0, 100)}% · $detail"
        percent >= 0 -> "${percent.coerceIn(0, 100)}%"
        else -> detail.ifBlank { "Starting download…" }
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).putExtra(OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(context: Context, id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, DownloadActionReceiver::class.java)
            .setAction(action).setData(Uri.parse("andrip://download/$id"))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(DownloadWorker.KEY_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun progress(context: Context, id: Long, title: String, percent: Int, detail: String): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(progressText(percent, detail))
            .setStyle(NotificationCompat.BigTextStyle().bigText(progressText(percent, detail)))
            .setSubText("Downloading")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent(context))
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_media_pause, "Pause", action(context, id, DownloadActionReceiver.PAUSE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", action(context, id, DownloadActionReceiver.CANCEL))
            .build()

    fun completed(context: Context, id: Long, fileName: String, segments: Int?) {
        val detail = if (segments == null) "$fileName · Saved on this device"
            else "$fileName · $segments of $segments segments saved"
        result(context, id, "Download complete", detail)
    }

    fun result(context: Context, id: Long, title: String, detail: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            context.getSystemService(NotificationManager::class.java).notify("download-result-$id", 1,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title).setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setContentIntent(contentIntent(context)).setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        } catch (e: SecurityException) { AppLog.w("Notification", "Notifications are disabled", e) }
    }

    fun clearResult(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel("download-result-$id", 1)
    }
}

/** Internal, immutable notification actions update Room and WorkManager together. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadWorker.KEY_ID, -1)
        if (id < 0 || intent.action !in setOf(PAUSE, CANCEL)) return
        AppLog.i("Queue", "Notification ${if (intent.action == PAUSE) "Pause" else "Cancel"} requested for #$id")
        val pending = goAsync()
        scope.launch {
            try {
                if (intent.action == PAUSE) DownloadQueue.pause(context.applicationContext, id)
                else DownloadQueue.cancel(context.applicationContext, id)
            } catch (e: Exception) { AppLog.e("Queue", "Could not update download #$id", e) }
            finally { pending.finish() }
        }
    }

    companion object {
        const val PAUSE = "com.andrip.browser.PAUSE_DOWNLOAD"
        const val CANCEL = "com.andrip.browser.CANCEL_DOWNLOAD"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
