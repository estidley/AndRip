package com.andrip.browser.download

import android.util.AtomicFile
import com.andrip.browser.util.FileNames
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Android 6–9 export without broad storage permission. Kept outside disposable transfer workdirs. */
internal object LegacyMediaSaver {
    suspend fun save(source: File, folder: File, displayName: String, journal: AtomicFile): File {
        folder.mkdirs()
        val state = ExportCheckpoint.read(journal) ?: JSONObject()
            .put("legacyName", "${java.util.UUID.randomUUID()}-${FileNames.sanitize(displayName.substringBeforeLast('.')).take(60)}.${legacyExtension(displayName)}")
            .also { ExportCheckpoint.write(journal, it) }
        val target = ownedFile(folder, state.getString("legacyName"))
        // Atomic rename publishes the file. A retry after process death reuses that publication.
        if (target.exists()) {
            if (target.length() == source.length() && target.length() > 0) return target
            throw IOException("Saved output changed. Remove this history entry and save again.")
        }
        StorageSpace.requireSpace(folder, source.length())
        val pending = File(target.path + ".pending")
        FileOutputStream(pending).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.fd.sync()
        }
        if (pending.length() <= 0 || pending.length() != source.length()) throw IOException("Saved output was incomplete. Retry saving.")
        coroutineContext.ensureActive()
        if (!pending.renameTo(target)) throw IOException("Could not finish saving. Retry saving.")
        return target
    }

    fun discardPending(folder: File, journal: AtomicFile) {
        val state = ExportCheckpoint.read(journal) ?: return
        val name = state.optString("legacyName").takeIf { it.isNotBlank() } ?: return
        File(ownedFile(folder, name).path + ".pending").delete()
        // Published files are independent of queue history and deliberately retained.
    }

    private fun legacyExtension(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return if (ext == "vtt" || ext == "ttml") ext else FileNames.videoExtension(displayName)
    }

    private fun ownedFile(folder: File, name: String): File = File(folder, name).also {
        if (it.canonicalFile.parentFile != folder.canonicalFile) throw IOException("Invalid saved output path.")
    }
}
