package com.andrip.browser

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.WorkInfo
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.data.DownloadEntity
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.download.DownloadQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class QueueTest {
    @After fun closeDatabase() { closeTestDatabase() }
    @Test fun recoveryRepairsMissingAndStaleWorkWithoutResumingUserStops() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        val dao = AppDatabase.get(context).downloads()
        suspend fun row(status: String) = dao.insert(DownloadEntity(url = "https://example.invalid/recover.mp4",
            kind = "FILE", title = "Recovery", pageUrl = null, referer = null, userAgent = null, status = status))
        val queued = row(DownloadStatus.QUEUED)
        val running = row(DownloadStatus.RUNNING)
        val paused = row(DownloadStatus.PAUSED)
        val canceled = row(DownloadStatus.CANCELED)
        DownloadQueue.recover(context)
        val manager = WorkManager.getInstance(context)
        for (id in listOf(queued, running)) {
            assertEquals(DownloadStatus.QUEUED, dao.get(id)!!.status)
            assertTrue(manager.getWorkInfosForUniqueWorkFlow("download-$id").first().any { it.state == WorkInfo.State.ENQUEUED })
        }
        // Simulate process death: Room still says RUNNING, but persistent scheduling is queued.
        dao.setStatus(running, DownloadStatus.RUNNING, null)
        DownloadQueue.recover(context)
        assertEquals(DownloadStatus.QUEUED, dao.get(running)!!.status)
        // An external WorkManager cancel must not silently resurrect a job.
        manager.cancelUniqueWork("download-$queued").await()
        DownloadQueue.recover(context)
        assertEquals(DownloadStatus.PAUSED, dao.get(queued)!!.status)
        assertEquals(DownloadStatus.PAUSED, dao.get(paused)!!.status)
        assertEquals(DownloadStatus.CANCELED, dao.get(canceled)!!.status)
        for (id in listOf(paused, canceled)) assertTrue(manager.getWorkInfosForUniqueWorkFlow("download-$id").first().isEmpty())
        for (id in listOf(queued, running, paused, canceled)) DownloadQueue.remove(context, id)
    }

    @Test fun stalePauseAndCancelCannotUndoSavedExport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        val dao = AppDatabase.get(context).downloads()
        val id = dao.insert(DownloadEntity(url = "https://example.invalid/done.mp4", kind = "FILE", title = "Saved",
            pageUrl = null, referer = null, userAgent = null))
        dao.setDone(id, "content://media/external/video/media/1", "saved.mp4")
        DownloadQueue.pause(context, id)
        DownloadQueue.cancel(context, id)
        assertEquals(DownloadStatus.DONE, dao.get(id)!!.status)
        assertEquals("saved.mp4", dao.get(id)!!.fileName)
        assertEquals(0, dao.transition(id, DownloadQueue.active, DownloadStatus.QUEUED, "late worker"))
        DownloadQueue.remove(context, id)
    }

    @Test fun concurrentEnqueueDeduplicatesAndPauseResumeCancelPersist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        val media = DetectedMedia("https://example.invalid/video.m3u8?signature=keep", MediaKind.HLS,
            "https://example.invalid/page", "Example", "https://example.invalid/embed", "Browser UA", "network",
            mapOf("Origin" to "https://example.invalid"), 720)
        // WorkManager's test driver leaves the network constraint unmet; no external requests occur.
        val ids = coroutineScope { (1..6).map { async { DownloadQueue.enqueue(context, media) } }.awaitAll() }
        assertEquals(1, ids.distinct().size)
        val id = ids.first()
        val dao = AppDatabase.get(context).downloads()
        assertEquals(720, dao.get(id)!!.maxHeight)
        assertTrue(dao.get(id)!!.headersJson.contains("Origin"))
        val otherQuality = DownloadQueue.enqueue(context, media.copy(maxHeight = 480))
        assertNotEquals(id, otherQuality)
        DownloadQueue.pause(context, id)
        assertEquals(DownloadStatus.PAUSED, dao.get(id)!!.status)
        DownloadQueue.retry(context, id)
        assertEquals(DownloadStatus.QUEUED, dao.get(id)!!.status)
        DownloadQueue.cancel(context, id)
        assertEquals(DownloadStatus.CANCELED, dao.get(id)!!.status)
        DownloadQueue.remove(context, id)
        assertNull(dao.get(id))
        DownloadQueue.remove(context, otherQuality)
    }

    @Test fun migrationKeepsExistingDownloadsAndAddsRequestContext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-check.db"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("""CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                url TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, pageUrl TEXT, referer TEXT,
                userAgent TEXT, status TEXT NOT NULL, progress INTEGER NOT NULL, detail TEXT NOT NULL,
                error TEXT, outputUri TEXT, fileName TEXT, createdAt INTEGER NOT NULL)""")
            db.execSQL("""INSERT INTO downloads (id,url,kind,title,status,progress,detail,createdAt)
                VALUES (7,'https://example.invalid/video.mp4','FILE','Existing download','DONE',100,'',1)""")
            db.version = 1
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val item = db.downloads().get(7)!!
            assertEquals("Existing download", item.title)
            assertEquals(DownloadStatus.DONE, item.status)
            assertEquals("{}", item.headersJson)
            assertNull(item.maxHeight)
        } finally { db.close() }
    }
}
