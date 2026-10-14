package com.andrip.browser

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.download.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class BackgroundTest {
    @After fun closeDatabase() { closeTestDatabase() }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dao get() = AppDatabase.get(context).downloads()

    @Test fun notificationActionsKeepDownloadIdsSeparateAndTapOpensDownloads() {
        Notifications.createChannel(context)
        val first = Notifications.progress(context, 7, "First", 42, "42 MB of 100 MB")
        val second = Notifications.progress(context, 8, "Second", -1, "Starting")
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent)
        assertNotEquals(first.actions[0].actionIntent, first.actions[1].actionIntent)
        val pause = shadowOf(first.actions[0].actionIntent).savedIntent
        val cancel = shadowOf(first.actions[1].actionIntent).savedIntent
        assertEquals(DownloadActionReceiver.PAUSE, pause.action)
        assertEquals(DownloadActionReceiver.CANCEL, cancel.action)
        assertTrue(pause.flags and android.content.Intent.FLAG_RECEIVER_FOREGROUND != 0)
        assertTrue(cancel.flags and android.content.Intent.FLAG_RECEIVER_FOREGROUND != 0)
        assertEquals(7, pause.getLongExtra(DownloadWorker.KEY_ID, -1))
        assertEquals(7, cancel.getLongExtra(DownloadWorker.KEY_ID, -1))
        assertEquals(DownloadActionReceiver::class.java.name, pause.component!!.className)
        val open = shadowOf(first.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component!!.className)
        assertTrue(open.getBooleanExtra(Notifications.OPEN_DOWNLOADS, false))
        assertEquals("42% · 42 MB of 100 MB", first.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun segmentNotificationShowsBarAndCountThenACompletedResult() {
        Notifications.createChannel(context)
        val progress = Notifications.progress(context, 9, "Movie", 42, "84 of 200 segments")
        assertEquals("42% · 84 of 200 segments", progress.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(42, progress.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, progress.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(progress.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(progress.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val unknown = Notifications.progress(context, 9, "Movie", -1, "Reading playlist…")
        assertTrue(unknown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        Notifications.completed(context, 9, "Movie.mp4", 200)
        val result = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("download-result-9", 1)
        assertNotNull(result)
        assertEquals("Download complete", result.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Movie.mp4 · 200 of 200 segments saved", result.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(0, result.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, result.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(result.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(shadowOf(result.contentIntent).savedIntent.getBooleanExtra(Notifications.OPEN_DOWNLOADS, false))
    }

    @Test fun progressiveCompletionDoesNotInventSegmentCounts() {
        Notifications.createChannel(context)
        Notifications.completed(context, 10, "Audio.m4a", null)
        val result = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("download-result-10", 1)
        assertEquals("Audio.m4a · Saved on this device", result.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    private suspend fun row(url: String): Long = dao.insert(DownloadEntity(
        url = url, kind = "FILE", title = "Background test", pageUrl = null, referer = null, userAgent = null))

    private fun worker(id: Long, attempt: Int = 0, foregroundAllowed: Boolean = true): DownloadWorker =
        TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id)).setRunAttemptCount(attempt)
            .setForegroundUpdater(object : ForegroundUpdater {
                override fun setForegroundAsync(context: Context, id: UUID, foregroundInfo: ForegroundInfo): ListenableFuture<Void> =
                    CallbackToFutureAdapter.getFuture { completer ->
                        if (foregroundAllowed) completer.set(null)
                        else completer.setException(IllegalStateException("Service start denied"))
                        "test foreground"
                    }
            }).build()

    @Test fun retryBudgetExcludesAccessErrorsAndUnsupportedMedia() {
        assertTrue(BackgroundPolicy.shouldRetry(IOException("offline"), 0))
        assertTrue(BackgroundPolicy.shouldRetry(HttpFailure(503), 1))
        assertFalse(BackgroundPolicy.shouldRetry(IOException("offline"), 2))
        for (code in listOf(401, 403, 404, 410)) assertFalse(BackgroundPolicy.shouldRetry(HttpFailure(code), 0))
        assertFalse(BackgroundPolicy.shouldRetry(UnsupportedStreamException("DRM"), 0))
        assertFalse(BackgroundPolicy.shouldRetry(IllegalStateException("foreground"), 0))
        assertFalse(BackgroundPolicy.shouldRetry(MediaFinalizationException("Cannot merge these tracks", IOException("mux")), 0))
    }

    @Test fun accessDeniedIsTerminalWithFreshLinkGuidance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val id = row(server.url("/signed.mp4?token=private").toString())
            assertEquals(ListenableWorker.Result.failure(), worker(id).doWork())
            assertEquals(DownloadStatus.FAILED, dao.get(id)!!.status)
            assertTrue(dao.get(id)!!.error!!.contains("fresh link"))
            assertFalse(dao.get(id)!!.error!!.contains("private"))
            assertEquals(1, server.requestCount)
            val notice = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("download-result-$id", 1)
            assertNotEquals("Download complete", notice.extras.getCharSequence(Notification.EXTRA_TITLE))
        }
    }

    @Test fun exhaustedRequestRetriesScheduleBoundedBackgroundRetry() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setHeader("Retry-After", "0")) }
            val id = row(server.url("/movie.mp4").toString())
            assertEquals(ListenableWorker.Result.retry(), withTimeout(10000) { worker(id).doWork() })
            assertEquals(DownloadStatus.QUEUED, dao.get(id)!!.status)
            assertTrue(dao.get(id)!!.error!!.contains("1/2"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun finalBackgroundAttemptStopsInsteadOfLooping() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setHeader("Retry-After", "0")) }
            val id = row(server.url("/movie.mp4").toString())
            assertEquals(ListenableWorker.Result.failure(), withTimeout(10000) { worker(id, attempt = 2).doWork() })
            assertEquals(DownloadStatus.FAILED, dao.get(id)!!.status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun deniedForegroundStartDoesNotStartAnUnprotectedTransfer() = runBlocking {
        MockWebServer().use { server ->
            val id = row(server.url("/movie.mp4").toString())
            assertEquals(ListenableWorker.Result.failure(), worker(id, foregroundAllowed = false).doWork())
            assertEquals(DownloadStatus.FAILED, dao.get(id)!!.status)
            assertTrue(dao.get(id)!!.error!!.contains("Open AndRip"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun systemStopQueuesButUserStopKeepsExplicitState() = runBlocking {
        for (explicit in listOf(null, DownloadStatus.PAUSED, DownloadStatus.CANCELED)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val id = row(server.url("/movie.mp4").toString())
                val task = launch(Dispatchers.IO) { worker(id).doWork() }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                if (explicit != null) dao.transition(id, DownloadQueue.active, explicit, null)
                withTimeout(3000) { task.cancelAndJoin() }
                assertEquals(explicit ?: DownloadStatus.QUEUED, dao.get(id)!!.status)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun staleScheduledWorkerDoesNotRestartCanceledOrCompletedRows() = runBlocking {
        for (state in listOf(DownloadStatus.PAUSED, DownloadStatus.CANCELED, DownloadStatus.FAILED, DownloadStatus.DONE)) {
            val id = row("https://example.invalid/never-request.mp4")
            dao.setStatus(id, state, null)
            assertEquals(ListenableWorker.Result.success(), worker(id).doWork())
            assertEquals(state, dao.get(id)!!.status)
        }
    }
}
