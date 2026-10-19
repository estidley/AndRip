package com.andrip.browser.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.log.AppLog
import com.andrip.browser.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The replacement waits until the canceled worker has closed its files. */
internal object WorkerFiles {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    val transfers = Semaphore(2)
    fun lock(id: Long) = locks.getOrPut(id) { Mutex() }
}

object DownloadQueue {
    private val mutex = Mutex()
    internal fun workName(id: Long) = "download-$id"
    internal val active = listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)

    suspend fun enqueue(context: Context, media: DetectedMedia): Long = mutex.withLock {
        if (media.protected) throw UnsupportedStreamException("This stream is DRM-protected and cannot be saved.")
        val dao = AppDatabase.get(context).downloads()
        dao.findDuplicate(media.url, media.maxHeight)?.let {
            if (it.status in active) {
                if (it.status == DownloadStatus.QUEUED) start(context, it.id)
                return@withLock it.id
            }
            if (it.status == DownloadStatus.DONE && withContext(Dispatchers.IO) { SavedOutput.readable(context, it.outputUri) }) return@withLock it.id
            WorkManager.getInstance(context).cancelUniqueWork(workName(it.id)).await()
            WorkerFiles.lock(it.id).withLock refreshLock@{
                val latest = dao.get(it.id) ?: return@refreshLock
                if (latest.status == DownloadStatus.DONE && withContext(Dispatchers.IO) { SavedOutput.readable(context, latest.outputUri) }) return@refreshLock
                // A new session may identify different bytes at the same URL. Drop old partials atomically while idle.
                withContext(Dispatchers.IO) {
                    val dir = DownloadWorker.workDir(context, it.id)
                    MediaSaver.discardPending(context, dir)
                    dir.deleteRecursively()
                }
                if (dao.refresh(it.id, JSONObject(media.headers).toString(), media.referer, media.userAgent, media.pageUrl) > 0) start(context, it.id)
            }
            return@withLock it.id
        }
        val id = dao.insert(DownloadEntity(
            url = media.url, kind = media.kind.name, title = FileNames.sanitize(media.pageTitle),
            pageUrl = media.pageUrl, referer = media.referer, userAgent = media.userAgent,
            headersJson = JSONObject(media.headers).toString(), maxHeight = media.maxHeight,
        ))
        start(context, id)
        AppLog.i("Queue", "Queued #$id ${media.kind} ${media.url}")
        id
    }

    suspend fun retry(context: Context, id: Long) = mutex.withLock {
        val dao = AppDatabase.get(context).downloads()
        val item = dao.get(id) ?: return@withLock
        if (item.status in active) return@withLock
        if (item.status == DownloadStatus.DONE) {
            if (withContext(Dispatchers.IO) { SavedOutput.readable(context, item.outputUri) }) return@withLock
            dao.transition(id, listOf(DownloadStatus.DONE), DownloadStatus.FAILED, "Saved file is missing. Download again.")
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id)).await()
        WorkerFiles.lock(id).withLock {
            // A finishing export may have committed while cancellation was being delivered.
            if (dao.transition(id, listOf(DownloadStatus.PAUSED, DownloadStatus.CANCELED, DownloadStatus.FAILED),
                    DownloadStatus.QUEUED, null) > 0) start(context, id)
        }
        Notifications.clearResult(context, id)
    }

    suspend fun pause(context: Context, id: Long) = mutex.withLock {
        val dao = AppDatabase.get(context).downloads()
        if (dao.transition(id, active, DownloadStatus.PAUSED, null) > 0) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id)).await()
        }
    }

    suspend fun cancel(context: Context, id: Long) = mutex.withLock { cancelLocked(context, id) }

    private suspend fun cancelLocked(context: Context, id: Long) {
        val dao = AppDatabase.get(context).downloads()
        if (dao.transition(id, active + DownloadStatus.PAUSED, DownloadStatus.CANCELED, null) > 0) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(id)).await()
        }
    }

    suspend fun remove(context: Context, id: Long) = mutex.withLock {
        cancelLocked(context, id)
        WorkerFiles.lock(id).withLock {
            withContext(Dispatchers.IO) {
                val dir = DownloadWorker.workDir(context, id)
                MediaSaver.discardPending(context, dir)
                dir.deleteRecursively()
            }
            AppDatabase.get(context).downloads().delete(id)
        }
        Notifications.clearResult(context, id)
    }

    /**
     * Cancel an active transfer, delete the published file (MediaStore or app storage) and the
     * private partials, then remove the list row. [remove] still keeps a finished export.
     */
    suspend fun delete(context: Context, id: Long) = mutex.withLock {
        cancelLocked(context, id)
        WorkerFiles.lock(id).withLock {
            val dao = AppDatabase.get(context).downloads()
            val item = dao.get(id)
            withContext(Dispatchers.IO) {
                val dir = DownloadWorker.workDir(context, id)
                SavedFileDeleter.deleteAll(context, item?.outputUri, dir)
                dir.deleteRecursively()
            }
            dao.delete(id)
        }
        Notifications.clearResult(context, id)
        AppLog.i("Queue", "Deleted #$id and its saved file")
    }

    /** Repair the small non-transactional gap between Room insertion and WorkManager enqueue. */
    suspend fun recover(context: Context) = mutex.withLock {
        val dao = AppDatabase.get(context).downloads()
        val manager = WorkManager.getInstance(context)
        for (item in dao.unfinished()) {
            val work = manager.getWorkInfosForUniqueWorkFlow(workName(item.id)).first()
            if (work.any { !it.state.isFinished }) {
                // A live worker owns this lock. Repair a stale RUNNING row only while it is idle.
                if (work.none { it.state == WorkInfo.State.RUNNING } && WorkerFiles.lock(item.id).tryLock()) {
                    try { dao.transition(item.id, listOf(DownloadStatus.RUNNING), DownloadStatus.QUEUED,
                        "Waiting for network or Android to allow background work.") }
                    finally { WorkerFiles.lock(item.id).unlock() }
                }
                continue
            }
            WorkerFiles.lock(item.id).withLock itemLock@{
                if (dao.get(item.id)?.status !in active) return@itemLock
                when {
                    work.any { it.state == WorkInfo.State.CANCELLED } ->
                        dao.transition(item.id, active, DownloadStatus.PAUSED, "Interrupted. Tap Resume to continue.")
                    work.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.SUCCEEDED } ->
                        dao.transition(item.id, active, DownloadStatus.FAILED, "The background task ended before saving. Tap Retry to continue.")
                    else -> {
                        dao.transition(item.id, active, DownloadStatus.QUEUED, null)
                        start(context, item.id)
                    }
                }
            }
        }
    }

    private suspend fun start(context: Context, id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (DownloadPreferences.unmeteredOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true).build())
            // Five minutes also respects the transport's capped Retry-After on a busy server.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request).await()
    }
}
