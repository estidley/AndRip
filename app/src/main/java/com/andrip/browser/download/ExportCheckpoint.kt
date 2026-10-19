package com.andrip.browser.download

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Private, atomic journal survives process death between transfer, publication and Room commit. */
internal class ExportCheckpoint(private val dir: File) {
    private val ready = AtomicFile(File(dir, "ready.json"))
    val publication = AtomicFile(File(dir, "publication.json"))

    fun record(output: DownloadEngine.Output) = write(ready, JSONObject()
        .put("file", output.file.name).put("length", output.file.length())
        .put("extension", output.extension).put("segments", output.completedSegments)
        .put("sidecar", output.sidecar?.name ?: ""))

    fun output(): DownloadEngine.Output? {
        val data = read(ready) ?: return null
        val file = File(dir, data.getString("file"))
        if (file.canonicalFile.parentFile != dir.canonicalFile || !file.isFile || file.length() <= 0 || file.length() != data.getLong("length")) return null
        val sideName = data.optString("sidecar")
        val side = sideName.takeIf {
            it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && it != "." && it != ".."
        }?.let { File(dir, it) }?.takeIf { it.isFile && it.canonicalFile.parentFile == dir.canonicalFile }
        return DownloadEngine.Output(file, data.getString("extension"),
            if (data.has("segments")) data.getInt("segments") else null, side)
    }

    companion object {
        fun read(file: AtomicFile): JSONObject? = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null else
            JSONObject(file.openRead().bufferedReader().use { it.readText() })
        fun write(file: AtomicFile, value: JSONObject) {
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try { stream.write(value.toString().toByteArray()); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
}

internal object StorageSpace {
    fun requireSpace(dir: File, additional: Long = 0) {
        dir.mkdirs()
        if (dir.usableSpace - 16L * 1024 * 1024 < additional.coerceAtLeast(0))
            throw StorageFullException()
    }
}

internal class StorageFullException : IOException("Not enough free storage. Free some space, then Retry. Completed local media will be reused when available.")

internal object SavedOutput {
    fun readable(context: Context, value: String?): Boolean = value != null && runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(value), "r")?.use { it.statSize != 0L } == true
    }.getOrDefault(false)
}
