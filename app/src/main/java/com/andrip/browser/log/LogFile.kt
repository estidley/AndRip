package com.andrip.browser.log

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text rotating log on disk. No Android imports, so it is unit-testable.
 *
 * Every entry is one line; when a Throwable is passed, the FULL stack trace
 * (including every "Caused by" and suppressed exception) follows it.
 */
class LogFile(private val dir: File, private val maxBytes: Long = 1_000_000L) {

    val current: File get() = File(dir, "andrip.log")
    private val previous: File get() = File(dir, "andrip.log.1")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun write(level: String, tag: String, message: String, error: Throwable? = null) {
        try {
            dir.mkdirs()
            if (current.length() > maxBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            val text = StringBuilder()
                .append(stamp.format(Date())).append(' ')
                .append(level).append('/').append(tag)
                .append(" [").append(Thread.currentThread().name).append("] ")
                .append(message).append('\n')
            if (error != null) text.append(stackTrace(error))
            FileOutputStream(current, true).use { out ->
                out.write(text.toString().toByteArray())
                // Make sure errors actually reach the disk before the process dies.
                if (error != null) out.fd.sync()
            }
        } catch (_: Throwable) {
            // Logging must never take the app down.
        }
    }

    /** Older rotated file first, then the current one. */
    @Synchronized
    fun readAll(): String = buildString {
        if (previous.exists()) append(previous.readText())
        if (current.exists()) append(current.readText())
    }

    /** The newest [maxChars] characters, for showing on screen. */
    fun tail(maxChars: Int): String {
        val all = readAll()
        return if (all.length <= maxChars) all else "…\n" + all.substring(all.length - maxChars).substringAfter('\n')
    }

    @Synchronized
    fun clear() {
        previous.delete()
        current.delete()
    }

    /** Writes everything into a single file suitable for attaching to a message. */
    @Synchronized
    fun export(target: File, header: String): File {
        target.parentFile?.mkdirs()
        target.writeText(header + "\n\n" + readAll())
        return target
    }

    companion object {
        fun stackTrace(error: Throwable): String {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer, true))
            return writer.toString()
        }
    }
}
