package com.andrip.browser.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * Deletes a finished export from app storage or MediaStore, plus any file still named in the
 * export journal. Paths outside AndRip's own export folder are refused. Pending MediaStore rows
 * are discarded as well. Private transfer directories are removed by the caller.
 */
internal object SavedFileDeleter {
    fun deleteAll(context: Context, outputUri: String?, workDir: File) {
        val targets = LinkedHashSet<String>()
        if (!outputUri.isNullOrBlank()) targets.add(outputUri)
        targets.addAll(journalTargets(context, workDir))
        for (target in targets) deleteUri(context, target)
        MediaSaver.discardPending(context, workDir)
    }

    fun deleteUri(context: Context, value: String) {
        val uri = Uri.parse(value)
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: throw IOException("Could not delete the saved file.")
                deleteOwned(context, File(path))
            }
            "content" -> {
                if (uri.authority == "${context.packageName}.files") {
                    val name = Uri.decode(uri.lastPathSegment ?: throw IOException("Could not delete the saved file."))
                    val file = legacyFile(context, name) ?: throw IOException("Refusing to delete a file outside AndRip storage.")
                    deleteOwned(context, file)
                } else {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: SecurityException) {
                    throw IOException("Could not delete the saved file.", e)
                } catch (e: UnsupportedOperationException) {
                    throw IOException("Could not delete the saved file.", e)
                }
                if (SavedOutput.readable(context, value)) throw IOException("Could not delete the saved file.")
                }
            }
            else -> throw IOException("Could not delete the saved file.")
        }
    }

    private fun journalTargets(context: Context, workDir: File): List<String> {
        val state = ExportCheckpoint.read(ExportCheckpoint(workDir).publication) ?: return emptyList()
        val targets = ArrayList<String>(2)
        val legacy = state.optString("legacyName")
        if (legacy.isNotBlank()) legacyFile(context, legacy)?.let { targets.add(it.toURI().toString()) }
        val uri = state.optString("uri")
        if (uri.isNotBlank()) targets.add(uri)
        return targets
    }

    internal fun legacyFile(context: Context, name: String): File? {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            return null
        }
        val root = legacyRoot(context) ?: return null
        val file = File(root, name).canonicalFile
        if (file.parentFile != root) return null
        return file
    }

    private fun deleteOwned(context: Context, file: File) {
        val root = legacyRoot(context) ?: throw IOException("Device storage is unavailable.")
        val canonical = file.canonicalFile
        if (canonical == root || canonical.parentFile != root) {
            throw IOException("Refusing to delete a file outside AndRip storage.")
        }
        if (canonical.exists() && !canonical.delete()) throw IOException("Could not delete the saved file.")
        File(canonical.path + ".pending").delete()
    }

    private fun legacyRoot(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "AndRip").canonicalFile }
}
