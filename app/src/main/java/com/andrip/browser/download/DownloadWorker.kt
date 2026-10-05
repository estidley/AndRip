package com.andrip.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.log.AppLog
import com.andrip.browser.util.FileNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

object Notifications {
    private const val CHANNEL = "downloads"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun progress(context: Context, workId: UUID, title: String, percent: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (percent >= 0) "$percent%" else "Downloading…")
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
            .build()
}

/** Queue operations used by the UI. */
object DownloadQueue {
    private fun workName(id: Long) = "download-$id"

    suspend fun enqueue(context: Context, media: DetectedMedia): Long {
        val dao = AppDatabase.get(context).downloads()
        val id = dao.insert(
            DownloadEntity(
                url = media.url,
                kind = media.kind.name,
                title = FileNames.sanitize(media.pageTitle),
                pageUrl = media.pageUrl,
                referer = media.referer,
                userAgent = media.userAgent,
            ),
        )
        start(context, id)
        AppLog.i("Queue", "Queued #$id ${media.kind} ${media.url}")
        return id
    }

    suspend fun retry(context: Context, id: Long) {
        AppDatabase.get(context).downloads().setStatus(id, DownloadStatus.QUEUED, null)
        start(context, id)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun remove(context: Context, id: Long) {
        cancel(context, id)
        AppDatabase.get(context).downloads().delete(id)
        withContext(Dispatchers.IO) { DownloadWorker.workDir(context, id).deleteRecursively() }
    }

    private fun start(context: Context, id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dao = AppDatabase.get(context).downloads()
    private var foregroundFailureLogged = false

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        val item = dao.get(id) ?: return Result.failure()
        val workDir = workDir(applicationContext, id)
        AppLog.i(TAG, "Start #$id ${item.kind} ${item.url} (referer=${item.referer})")
        dao.setStatus(id, DownloadStatus.RUNNING, null)
        showProgress(item.title, -1)

        return try {
            val output = fetch(item, workDir)
            val name = "${item.title}.${output.extension}"
            val uri = withContext(Dispatchers.IO) {
                MediaSaver.save(applicationContext, output.file, name, FileNames.mimeFor(output.extension))
            }
            workDir.deleteRecursively()
            dao.setDone(id, uri.toString(), name)
            AppLog.i(TAG, "Done #$id -> $name ($uri)")
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.setStatus(id, DownloadStatus.CANCELED, null) }
            AppLog.i(TAG, "Canceled #$id")
            throw e
        } catch (e: Throwable) {
            // Full stack trace goes to the log file; the short message goes on screen.
            AppLog.e(TAG, "Failed #$id ${item.kind} ${item.url}", e)
            dao.setStatus(id, DownloadStatus.FAILED, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    private suspend fun fetch(item: DownloadEntity, workDir: File): DownloadEngine.Output = coroutineScope {
        val headers = RequestHeaders(item.referer, item.userAgent)
        val engine = DownloadEngine()
        val latest = MutableStateFlow(-1 to "")
        // The engine reports progress very often; write it out at most every 750 ms.
        val reporter = launch {
            latest.collect { (percent, detail) ->
                dao.setProgress(item.id, percent, detail)
                showProgress(item.title, percent)
                delay(750)
            }
        }
        try {
            when (item.kind) {
                MediaKind.HLS.name ->
                    engine.downloadHls(item.url, headers, workDir) { percent, detail -> latest.value = percent to detail }

                MediaKind.FILE.name -> {
                    val extension = FileNames.videoExtension(item.url)
                    val target = File(workDir, "download.$extension")
                    engine.downloadFile(item.url, headers, target) { percent, detail -> latest.value = percent to detail }
                    DownloadEngine.Output(target, extension)
                }

                MediaKind.DASH.name ->
                    engine.downloadDash(item.url, headers, workDir) { percent, detail -> latest.value = percent to detail }

                else -> throw UnsupportedStreamException("${item.kind} streams aren't supported.")
            }
        } finally {
            reporter.cancel()
        }
    }

    private suspend fun showProgress(title: String, percent: Int) {
        try {
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_BASE + (inputData.getLong(KEY_ID, 0L) % 100_000).toInt(),
                    Notifications.progress(applicationContext, id, title, percent),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not fatal: the download just runs without a notification.
            if (!foregroundFailureLogged) {
                foregroundFailureLogged = true
                AppLog.w(TAG, "Could not show the download notification", e)
            }
        }
    }

    companion object {
        const val KEY_ID = "download_id"
        private const val TAG = "Download"
        private const val NOTIFICATION_BASE = 4000

        fun workDir(context: Context, id: Long) = File(context.cacheDir, "downloads/$id")
    }
}

/** Copies a finished download into shared storage so it shows up in the gallery and Files. */
object MediaSaver {
    fun save(context: Context, source: File, displayName: String, mimeType: String): Uri = try {
        insert(
            context,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            Environment.DIRECTORY_MOVIES + "/AndRip",
            source, displayName, mimeType,
        )
    } catch (e: Exception) {
        AppLog.w("Save", "Movies folder rejected $displayName ($mimeType); using Download instead", e)
        insert(
            context,
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            Environment.DIRECTORY_DOWNLOADS + "/AndRip",
            source, displayName, mimeType,
        )
    }

    private fun insert(
        context: Context,
        collection: Uri,
        relativePath: String,
        source: File,
        displayName: String,
        mimeType: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Storage refused to create $displayName")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $uri for writing")
            out.use { stream -> source.inputStream().use { it.copyTo(stream, 256 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
