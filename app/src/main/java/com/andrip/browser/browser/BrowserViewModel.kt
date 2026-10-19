package com.andrip.browser.browser

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaDetector
import com.andrip.browser.download.DownloadQueue
import com.andrip.browser.log.AppLog
import com.andrip.browser.util.UrlInput
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class BrowserViewModel(private val app: Application) : AndroidViewModel(app) {
    private val mainThread = Handler(Looper.getMainLooper())
    private val dao = AppDatabase.get(app).downloads()

    var pageError by mutableStateOf<String?>(null)
    var queueMessage by mutableStateOf<String?>(null)
    var openDownloads by mutableStateOf(false)
    @Volatile private var pageGeneration = 0L
    var url by mutableStateOf(UrlInput.HOME)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** The WebView's user agent, copied onto downloads so servers see the same client. */
    @Volatile var userAgent: String? = null

    private val adBlockState = mutableStateOf(com.andrip.browser.browser.AdBlockPreferences.enabled(app))

    /** WebView's request thread reads this. Compose reads [adBlockEnabled]. */
    @Volatile private var adBlockSnapshot = adBlockState.value

    var adBlockEnabled: Boolean
        get() = adBlockState.value
        set(value) {
            adBlockSnapshot = value
            adBlockState.value = value
            com.andrip.browser.browser.AdBlockPreferences.set(app, value)
        }

    fun adBlockActive(): Boolean = adBlockSnapshot

    /** Videos spotted on the current page. Only touched on the main thread. */
    val detected = mutableStateListOf<DetectedMedia>()

    val downloads: StateFlow<List<DownloadEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onPageStarted(newUrl: String) {
        pageGeneration++
        pageError = null
        url = newUrl
        loading = true
        progress = 0
        detected.clear()
        AppLog.d(TAG, "Page: $newUrl")
    }

    /**
     * Called for every request the page makes and every URL the injected script reports.
     * Safe from any thread: classification happens here, the list is updated on the main thread.
     */
    fun report(mediaUrl: String, mimeType: String?, referer: String?, source: String, headers: Map<String, String> = emptyMap()) {
        if (MediaDetector.looksLikeAd(mediaUrl)) return
        val kind = MediaDetector.classify(mediaUrl, mimeType) ?: return
        val generation = pageGeneration
        mainThread.post {
            if (generation != pageGeneration) return@post
            val previous = detected.indexOfFirst { it.url == mediaUrl }
            if (previous < 0) {
                if (detected.size >= 200) return@post
                detected.add(
                    DetectedMedia(
                        url = mediaUrl,
                        kind = kind,
                        pageUrl = url,
                        pageTitle = title.ifBlank { null },
                        referer = referer ?: url,
                        userAgent = userAgent,
                        source = source,
                        headers = com.andrip.browser.download.RequestHeaders.capture(headers),
                    ),
                )
                AppLog.i(TAG, "Found $kind via $source: $mediaUrl")
            } else if (source == "network") {
                detected[previous] = detected[previous].copy(referer = referer ?: detected[previous].referer,
                    headers = com.andrip.browser.download.RequestHeaders.capture(headers), source = source)
            }
        }
    }

    fun download(media: DetectedMedia) {
        viewModelScope.launch {
            try {
                val result = DownloadQueue.enqueue(app, media)
                queueMessage = when (dao.get(result)?.status) {
                    com.andrip.browser.data.DownloadStatus.DONE -> "Already saved on this device"
                    com.andrip.browser.data.DownloadStatus.RUNNING -> "Already downloading"
                    else -> "Download #$result queued with the current source details"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { queueMessage = e.message ?: "Could not add download" }
        }
    }

    fun retry(id: Long) {
        queueAction { DownloadQueue.retry(app, id) }
    }

    fun openSaved(item: DownloadEntity, open: () -> Unit) {
        queueAction {
            if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.andrip.browser.download.SavedOutput.readable(app, item.outputUri) }) open()
            else {
                dao.transition(item.id, listOf(com.andrip.browser.data.DownloadStatus.DONE), com.andrip.browser.data.DownloadStatus.FAILED, "Saved file is missing. Tap Retry to download again.")
                queueMessage = "Saved file is missing. Tap Retry to download again."
            }
        }
    }

    fun cancel(id: Long) { queueAction { DownloadQueue.cancel(app, id) } }

    fun pause(id: Long) { queueAction { DownloadQueue.pause(app, id) } }

    fun remove(id: Long) {
        queueAction { DownloadQueue.remove(app, id) }
    }

    /** Cancels an in-progress transfer first, deletes the saved file, then drops the list row. */
    fun deleteDownloads(ids: Collection<Long>) {
        queueAction {
            var failed = 0
            for (id in ids) {
                try {
                    DownloadQueue.delete(app, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    AppLog.w(TAG, "Could not delete download #$id", e)
                }
            }
            if (failed > 0) queueMessage = if (failed == ids.size) "Could not delete the saved file." else "Some files could not be deleted."
        }
    }

    private fun queueAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { queueMessage = e.message ?: "Could not update this download" }
        }
    }

    private companion object {
        const val TAG = "Browser"
    }
}
