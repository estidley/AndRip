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

class BrowserViewModel(private val app: Application) : AndroidViewModel(app) {
    private val mainThread = Handler(Looper.getMainLooper())
    private val dao = AppDatabase.get(app).downloads()

    var url by mutableStateOf(UrlInput.HOME)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** The WebView's user agent, copied onto downloads so servers see the same client. */
    @Volatile var userAgent: String? = null

    /** Videos spotted on the current page. Only touched on the main thread. */
    val detected = mutableStateListOf<DetectedMedia>()

    val downloads: StateFlow<List<DownloadEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onPageStarted(newUrl: String) {
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
    fun report(mediaUrl: String, mimeType: String?, referer: String?, source: String) {
        val kind = MediaDetector.classify(mediaUrl, mimeType) ?: return
        mainThread.post {
            if (detected.none { it.url == mediaUrl }) {
                detected.add(
                    DetectedMedia(
                        url = mediaUrl,
                        kind = kind,
                        pageUrl = url,
                        pageTitle = title.ifBlank { null },
                        referer = referer ?: url,
                        userAgent = userAgent,
                        source = source,
                    ),
                )
                AppLog.i(TAG, "Found $kind via $source: $mediaUrl")
            }
        }
    }

    fun download(media: DetectedMedia) {
        viewModelScope.launch { DownloadQueue.enqueue(app, media) }
    }

    fun retry(id: Long) {
        viewModelScope.launch { DownloadQueue.retry(app, id) }
    }

    fun cancel(id: Long) = DownloadQueue.cancel(app, id)

    fun remove(id: Long) {
        viewModelScope.launch { DownloadQueue.remove(app, id) }
    }

    private companion object {
        const val TAG = "Browser"
    }
}
