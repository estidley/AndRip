package com.andrip.browser.download

import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.data.DownloadStatus
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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.IOException

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dao = AppDatabase.get(context).downloads()

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        return WorkerFiles.lock(id).withLock { WorkerFiles.transfers.withPermit { performWork(id) } }
    }

    private suspend fun performWork(id: Long): Result {
        val item = dao.get(id) ?: return Result.failure()
        val workDir = workDir(applicationContext, id)
        AppLog.i(TAG, "Start #$id ${item.kind} ${item.url} (referer=${item.referer})")
        if (dao.transition(id, DownloadQueue.active, DownloadStatus.RUNNING, null) == 0) return Result.success()
        var transferring = false
        return try {
            showProgress(item.title, -1, "Starting download…")
            val checkpoint = ExportCheckpoint(workDir)
            val output = withContext(Dispatchers.IO) { checkpoint.output() } ?: run {
                withContext(Dispatchers.IO) { StorageSpace.requireSpace(workDir) }
                transferring = true
                val fetched = fetch(item, workDir)
                withContext(Dispatchers.IO) {
                    MediaProtection.checkFile(fetched.file)
                    checkpoint.record(fetched)
                }
                fetched
            }
            transferring = false
            val saving = output.completedSegments?.let { "$it of $it segments · Saving to your device…" }
                ?: "Saving to your device…"
            dao.setProgress(id, 100, saving)
            showProgress(item.title, 100, saving)
            withContext(Dispatchers.IO) { MediaProtection.checkFile(output.file) }
            val name = "${item.title}.${output.extension}"
            val uri = withContext(Dispatchers.IO) {
                MediaSaver.save(applicationContext, output.file, name, FileNames.mimeFor(output.extension), checkpoint.publication).also {
                    // Commit before switching dispatchers, so cancellation cannot discard a published URI.
                    withContext(NonCancellable) {
                        dao.setDone(id, it.toString(), name)
                        Notifications.completed(applicationContext, id, name, output.completedSegments)
                    }
                }
            }
            output.sidecar?.let { side ->
                val ext = side.extension.ifEmpty { "vtt" }
                val sideName = "${item.title}.$ext"
                val sideMime = if (ext == "ttml") "application/ttml+xml" else "text/vtt"
                runCatching {
                    withContext(Dispatchers.IO) {
                        MediaSaver.save(
                            applicationContext,
                            side,
                            sideName,
                            sideMime,
                            android.util.AtomicFile(File(workDir, "subtitle-publication.json")),
                        )
                    }
                }.onFailure { error -> AppLog.w(TAG, "Subtitles were downloaded but not published", error) }
            }
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
            AppLog.i(TAG, "Done #$id -> $name ($uri)")
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Explicit Pause/Cancel is persisted first. A system stop is resumable.
                dao.transition(id, listOf(DownloadStatus.RUNNING), DownloadStatus.QUEUED,
                    "Waiting for network or Android to allow background work.")
            }
            AppLog.i(TAG, "Stopped #$id; progress retained")
            throw e
        } catch (e: Exception) {
            // Full stack trace goes to the log file; the short message goes on screen.
            AppLog.e(TAG, "Failed #$id ${item.kind} ${item.url}", e)
            if (transferring && BackgroundPolicy.shouldRetry(e, runAttemptCount)) {
                val message = "Connection interrupted. Automatic retry ${runAttemptCount + 1}/${BackgroundPolicy.MAX_RETRIES} is scheduled."
                dao.transition(id, DownloadQueue.active, DownloadStatus.QUEUED, message)
                Result.retry()
            } else {
                val message = if (runCatching { ExportCheckpoint(workDir).output() }.getOrNull() != null) "Retry saving: ${HttpPolicy.message(e)}" else HttpPolicy.message(e)
                if (dao.transition(id, DownloadQueue.active, DownloadStatus.FAILED, message) > 0) {
                    Notifications.result(applicationContext, id, item.title, message)
                }
                Result.failure()
            }
        }
    }

    private suspend fun fetch(item: DownloadEntity, workDir: File): DownloadEngine.Output = coroutineScope {
        val json = JSONObject(item.headersJson)
        val captured = json.keys().asSequence().associateWith { json.getString(it) }
        val headers = RequestHeaders(item.referer, item.userAgent, item.url, captured)
        if (com.andrip.browser.detect.MediaDetector.isProtectedUrl(item.url)) throw UnsupportedStreamException(MediaProtection.PROTECTED)
        val engine = DownloadEngine()
        val latest = MutableStateFlow(-1 to "")
        // The engine reports progress very often; write it out at most every 750 ms.
        val reporter = launch {
            latest.collect { (percent, detail) ->
                dao.setProgress(item.id, percent, detail)
                showProgress(item.title, percent, detail)
                delay(750)
            }
        }
        try {
            when (item.kind) {
                MediaKind.HLS.name ->
                    engine.downloadHls(item.url, headers, workDir, item.maxHeight) { percent, detail -> latest.value = percent to detail }

                MediaKind.FILE.name -> {
                    val extension = FileNames.videoExtension(item.url)
                    val target = File(workDir, "download.$extension")
                    val mime = engine.downloadFile(item.url, headers, target) { percent, detail -> latest.value = percent to detail }
                    DownloadEngine.Output(target, FileNames.extensionForMime(mime) ?: extension)
                }

                MediaKind.DASH.name ->
                    engine.downloadDash(item.url, headers, workDir, item.maxHeight) { percent, detail -> latest.value = percent to detail }

                else -> throw UnsupportedStreamException("${item.kind} streams aren't supported.")
            }
        } finally {
            reporter.cancel()
        }
    }

    private suspend fun showProgress(title: String, percent: Int, detail: String) {
        try {
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_BASE + (inputData.getLong(KEY_ID, 0L) % 100_000).toInt(),
                    Notifications.progress(applicationContext, inputData.getLong(KEY_ID, -1), title, percent, detail),
                    if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Android could not start the download notification/service. Open AndRip and tap Retry.", e)
        }
    }

    companion object {
        const val KEY_ID = "download_id"
        private const val TAG = "Download"
        private const val NOTIFICATION_BASE = 4000

        fun workDir(context: Context, id: Long) = File(context.noBackupFilesDir, "downloads/$id")
    }
}

/** Publishes only verified bytes. A durable token recovers even an insert-before-journal process death. */
object MediaSaver {
    suspend fun save(context: Context, source: File, displayName: String, mimeType: String,
        journal: android.util.AtomicFile = android.util.AtomicFile(File(source.parentFile, "publication.json"))): Uri {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("Device storage is unavailable. Retry saving when it is mounted.")
            val saved = LegacyMediaSaver.save(source, File(folder, "AndRip"), displayName, journal)
            return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", saved)
        }
        return saveShared(context, source, displayName, mimeType, journal)
    }

    @androidx.annotation.RequiresApi(29)
    private suspend fun saveShared(context: Context, source: File, displayName: String, mimeType: String,
        journal: android.util.AtomicFile): Uri {
        val resolver = context.contentResolver
        val state = ExportCheckpoint.read(journal) ?: JSONObject()
            .put("token", "andrip-pending-${java.util.UUID.randomUUID()}.${displayName.substringAfterLast('.', "mp4")}")
            .also { ExportCheckpoint.write(journal, it) }
        val token = state.getString("token")
        val movies = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var uri = state.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
        if (uri == null) {
            // The private random token is saved before insertion; only rows owned by this app are recovered.
            for (collection in listOf(movies, downloads)) {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf(token, context.packageName), null)?.use { cursor ->
                    if (cursor.moveToFirst()) uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
        }
        if (uri == null) {
            StorageSpace.requireSpace(source.parentFile!!, source.length())
            fun insert(collection: Uri, folder: String): Uri? = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, token)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/AndRip")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
            uri = try { insert(downloads, Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null }
                ?: insert(movies, Environment.DIRECTORY_MOVIES)
                ?: throw IOException("Storage refused to create the saved video.")
        }
        val target = checkNotNull(uri)
        state.put("uri", target.toString())
        ExportCheckpoint.write(journal, state)
        val pending = resolver.query(target, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
        if (pending == 0 && verify(context, target, source.length())) return target
        if (pending == 0) throw IOException("The saved output changed. Remove this history entry and save again.")
        StorageSpace.requireSpace(source.parentFile!!, source.length())
        val out = resolver.openOutputStream(target, "wt") ?: throw IOException("Could not open saved output for writing.")
        out.use { stream -> source.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                stream.write(buffer, 0, count)
            }
        } }
        if (!verify(context, target, source.length())) throw IOException("Saved output size could not be verified. Retry saving.")
        val published = resolver.update(target, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        }, null, null)
        if (published != 1) throw IOException("Storage did not publish the saved video. Retry saving.")
        val visible = resolver.query(target, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) == 0
        } == true
        if (!visible || !verify(context, target, source.length())) throw IOException("Saved output publication could not be verified. Retry saving.")
        return target
    }

    private fun verify(context: Context, uri: Uri, expected: Long): Boolean = expected > 0 && runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize == expected } == true
    }.getOrDefault(false)

    internal fun discardPending(context: Context, dir: File) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            LegacyMediaSaver.discardPending(File(folder, "AndRip"), ExportCheckpoint(dir).publication)
            return
        }
        discardSharedPending(context, dir)
    }

    @androidx.annotation.RequiresApi(29)
    private fun discardSharedPending(context: Context, dir: File) {
        val state = ExportCheckpoint.read(ExportCheckpoint(dir).publication) ?: return
        val token = state.optString("token")
        for (collection in listOf(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            context.contentResolver.delete(collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf(token, context.packageName))
        }
    }
}
