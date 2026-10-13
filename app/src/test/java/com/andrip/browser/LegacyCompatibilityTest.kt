package com.andrip.browser

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.andrip.browser.download.LegacyMediaSaver
import com.andrip.browser.download.Notifications
import com.andrip.browser.download.HttpPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 26, 28], manifest = Config.NONE, application = Application::class)
class LegacyCompatibilityTest {
    @Test fun notificationsWorkWithoutRequiringChannelsOnMarshmallow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Notifications.createChannel(context)
        Notifications.enabled(context)
        val notification = Notifications.progress(context, 5, "Media", 50, "5 of 10 segments")
        assertEquals(50, notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertEquals(2, notification.actions.size)
        if (Build.VERSION.SDK_INT < 26) assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Notifications.settingsIntent(context).action)
    }

    @Test fun legacyExportSurvivesJournalRestartAndHistoryRemoval() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "legacy-export").apply { mkdirs() }
        try {
            val source = File(root, "source.mp4").apply { writeText("synthetic-media-bytes") }
            val folder = File(root, "saved")
            val journal = AtomicFile(File(root, "publication.json"))
            val saved = LegacyMediaSaver.save(source, folder, "a".repeat(80)+".mp4", journal)
            assertTrue(saved.name.endsWith(".mp4"))
            assertEquals(source.readText(), saved.readText())
            assertEquals(saved, LegacyMediaSaver.save(source, folder, "ignored.mp4", AtomicFile(journal.baseFile)))
            assertEquals(1, folder.listFiles()!!.size)
            val pending = File(saved.path+".pending").apply { writeText("interrupted") }
            LegacyMediaSaver.discardPending(folder, journal)
            assertFalse(pending.exists())
            assertTrue(saved.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun datedRetryAfterIsAvailableOnOldAndroid() {
        assertEquals(1000L, HttpPolicy.retryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 0))
    }
}
