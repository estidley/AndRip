package com.andrip.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.download.DownloadQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fresh source context replaces stopped work; missing saved files can be downloaded again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class QueueRegressionTest {
    @Test fun retryMissingOutputSchedulesAgainWithoutRediscovery() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val id = DownloadQueue.enqueue(context, media("fake-token"))
        DownloadQueue.cancel(context, id)
        dao.setDone(id, "content://media/external/video/media/999999", "missing.mp4")
        DownloadQueue.retry(context, id)
        assertEquals(DownloadStatus.QUEUED, dao.get(id)!!.status)
        DownloadQueue.remove(context, id)
    }

    @Test fun rediscoveryDoesNotMutateRunningCredentials() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val id = DownloadQueue.enqueue(context, media("old-fake-token"))
        dao.setStatus(id, DownloadStatus.RUNNING, null)
        assertEquals(id, DownloadQueue.enqueue(context, media("new-fake-token")))
        assertTrue(dao.get(id)!!.headersJson.contains("old-fake-token"))
        assertEquals(DownloadStatus.RUNNING, dao.get(id)!!.status)
        DownloadQueue.remove(context, id)
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun initWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
    }
    @After fun cleanup() { closeTestDatabase() }
    private fun media(token: String) = DetectedMedia("https://example.invalid/movie.mp4", MediaKind.FILE,
        "https://example.invalid/page", "Audit", "https://example.invalid/page", "UA", "network",
        mapOf("Authorization" to "Bearer $token"))

    @Test fun rediscoveryRefreshesStoppedCredentials() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val id = DownloadQueue.enqueue(context, media("old-fake-token"))
        DownloadQueue.cancel(context, id)
        dao.setStatus(id, DownloadStatus.FAILED, "HTTP 403")
        val rediscovered = DownloadQueue.enqueue(context, media("new-fake-token"))
        assertEquals(id, rediscovered)
        val row = dao.get(id)!!
        assertEquals(DownloadStatus.QUEUED, row.status)
        assertFalse(row.headersJson.contains("old-fake-token"))
        assertTrue(row.headersJson.contains("new-fake-token"))
        DownloadQueue.retry(context, id)
        assertTrue(dao.get(id)!!.headersJson.contains("new-fake-token"))
        DownloadQueue.remove(context, id)
    }

    @Test fun deletedSavedFileCanBeRequeued() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val item = media("fake-token")
        val id = DownloadQueue.enqueue(context, item)
        DownloadQueue.cancel(context, id)
        dao.setDone(id, "content://media/external/video/media/999999", "missing.mp4")
        assertEquals(id, DownloadQueue.enqueue(context, item))
        DownloadQueue.retry(context, id)
        assertEquals(DownloadStatus.QUEUED, dao.get(id)!!.status)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("download-$id").first()
        assertTrue(work.any { !it.state.isFinished })
        DownloadQueue.remove(context, id)
    }
}
