package com.andrip.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.download.DownloadQueue
import com.andrip.browser.download.DownloadWorker
import com.andrip.browser.download.SavedFileDeleter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DownloadDeleteTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun initWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
    }

    @After fun cleanup() { closeTestDatabase() }

    @Test fun removeHistoryKeepsTheSavedFile() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val file = owned("kept.mp4")
        val id = DownloadQueue.enqueue(context, media())
        DownloadQueue.cancel(context, id)
        dao.setDone(id, file.toURI().toString(), "kept.mp4")
        DownloadQueue.remove(context, id)
        assertNull(dao.get(id))
        assertEquals("media", file.readText())
    }

    @Test fun deleteRemovesTheSavedFileAndTheRow() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val file = owned("gone.mp4")
        val id = DownloadQueue.enqueue(context, media())
        DownloadQueue.cancel(context, id)
        dao.setDone(id, file.toURI().toString(), "gone.mp4")
        DownloadQueue.delete(context, id)
        assertNull(dao.get(id))
        assertFalse(file.exists())
    }

    @Test fun deleteCancelsAnInProgressDownloadAndDropsPartials() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val id = DownloadQueue.enqueue(context, media())
        val partial = File(DownloadWorker.workDir(context, id), "partial.bin").apply {
            parentFile?.mkdirs()
            writeText("partial")
        }
        DownloadQueue.delete(context, id)
        assertNull(dao.get(id))
        assertFalse(partial.exists())
    }

    @Test fun deleteOfAMissingContentUriStillDropsTheRow() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val id = DownloadQueue.enqueue(context, media())
        DownloadQueue.cancel(context, id)
        dao.setDone(id, "content://media/external/video/media/55", "remote.mp4")
        DownloadQueue.delete(context, id)
        assertNull(dao.get(id))
    }

    @Test fun refusedFileDeleteKeepsTheRow() = runBlocking {
        val dao = AppDatabase.get(context).downloads()
        val outside = File(context.cacheDir, "outside.mp4").apply { writeText("keep") }
        val id = DownloadQueue.enqueue(context, media())
        DownloadQueue.cancel(context, id)
        dao.setDone(id, outside.toURI().toString(), "outside.mp4")
        val failed = runCatching { DownloadQueue.delete(context, id) }
        assertTrue(failed.isFailure)
        assertEquals(DownloadStatus.DONE, dao.get(id)!!.status)
        assertEquals("keep", outside.readText())
    }

    private fun owned(name: String): File {
        val file = SavedFileDeleter.legacyFile(context, name)!!
        file.parentFile?.mkdirs()
        file.writeText("media")
        return file
    }

    private fun media() = DetectedMedia(
        "https://example.invalid/movie.mp4",
        MediaKind.FILE,
        "https://example.invalid/page",
        "Clip",
        "https://example.invalid/page",
        "UA",
        "network",
    )
}
