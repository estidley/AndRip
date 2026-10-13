package com.andrip.browser

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.andrip.browser.download.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ExportRegressionTest {
    private class Provider(val file: File) : ContentProvider() {
        var pending = 1
        var inserts = 0
        var writes = 0
        var failPublication = false
        var name = ""
        override fun onCreate() = true
        override fun getType(uri: Uri) = "video/mp4"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns.IS_PENDING)
            return MatrixCursor(columns).apply {
                if (inserts > 0 && (selection == null || selectionArgs?.first() == name))
                    addRow(columns.map { if (it == MediaStore.MediaColumns._ID) 1 else pending }.toTypedArray())
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++; name = values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            return Uri.withAppendedPath(uri, "1")
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failPublication) return 0
            pending = values!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode.contains('w')) writes++
            return ParcelFileDescriptor.open(file, if (mode.contains('w'))
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
                else ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    @Test fun failedPublicationRetriesLocalBytesAndRecoversCommittedUriWithoutDuplicate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "export-test").apply { mkdirs() }
        val source = File(dir, "source.mp4").apply { writeText("synthetic-media") }
        val provider = Provider(File(dir, "target"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val journal = AtomicFile(File(dir, "publication.json"))
        try {
            provider.failPublication = true
            try { MediaSaver.save(context, source, "saved.mp4", "video/mp4", journal); fail("must refuse zero-row publication") }
            catch (e: IOException) { assertTrue(e.message!!.contains("publish")) }
            assertTrue(source.exists())
            assertEquals(1, provider.inserts)
            provider.failPublication = false
            val uri = MediaSaver.save(context, source, "saved.mp4", "video/mp4", journal)
            assertEquals(0, provider.pending)
            assertEquals(source.readText(), provider.file.readText())
            assertEquals(1, provider.inserts)
            val writes = provider.writes
            // Simulate process restart after publication but before Room completion.
            assertEquals(uri, MediaSaver.save(context, source, "saved.mp4", "video/mp4", AtomicFile(journal.baseFile)))
            assertEquals(writes, provider.writes)
            assertEquals(1, provider.inserts)
        } finally { dir.deleteRecursively() }
    }

    @Test fun insertBeforeUriJournalIsRecoveredByPrivateToken() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "insert-gap").apply { mkdirs() }
        val source = File(dir, "source.mp4").apply { writeText("synthetic") }
        val provider = Provider(File(dir, "target"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val journal = AtomicFile(File(dir, "publication.json"))
        ExportCheckpoint.write(journal, org.json.JSONObject().put("token", "private-test-token"))
        provider.inserts = 1; provider.name = "private-test-token"
        try {
            MediaSaver.save(context, source, "saved.mp4", "video/mp4", journal)
            assertEquals(1, provider.inserts)
        } finally { dir.deleteRecursively() }
    }

    @Test fun completedTransferCheckpointValidatesIdentityAndLength() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "ready-test").apply { mkdirs() }
        try {
            val file = File(dir, "finished.mp4").apply { writeText("complete-bytes") }
            val checkpoint = ExportCheckpoint(dir)
            checkpoint.record(DownloadEngine.Output(file, "mp4", 42))
            assertEquals(42, ExportCheckpoint(dir).output()!!.completedSegments)
            file.writeText("truncated")
            assertNull(checkpoint.output())
        } finally { dir.deleteRecursively() }
    }
}
