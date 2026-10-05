package com.andrip.browser.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.andrip.browser.BuildConfig
import java.io.File

/**
 * App-wide logger. Everything goes to logcat AND to files/logs/andrip.log.
 *
 * [init] also installs a crash handler, so an uncaught exception on any thread
 * is written to the log with its full stack trace before the app dies.
 * The Logs screen (menu > Logs) can share the file.
 */
object AppLog {
    private const val LOGCAT_TAG = "AndRip"

    @Volatile private var logFile: LogFile? = null
    private var header = "AndRip"

    fun init(context: Context) {
        if (logFile != null) return
        val file = LogFile(File(context.filesDir, "logs"))
        logFile = file
        header = "AndRip ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
            " | ${Build.MANUFACTURER} ${Build.MODEL}" +
            " | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " | WebView ${webViewVersion()}"

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            file.write("CRASH", "Uncaught", "Uncaught exception on thread \"${thread.name}\"", error)
            // Hand over to Android's own handler so the process still ends normally.
            previous?.uncaughtException(thread, error)
        }
        i("App", "==== started: $header ====")
    }

    fun d(tag: String, message: String) = log(Log.DEBUG, "D", tag, message, null)
    fun i(tag: String, message: String) = log(Log.INFO, "I", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Log.WARN, "W", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Log.ERROR, "E", tag, message, error)

    private fun log(priority: Int, level: String, tag: String, message: String, error: Throwable?) {
        val text = if (error != null) message + "\n" + LogFile.stackTrace(error) else message
        Log.println(priority, LOGCAT_TAG, "$tag: $text")
        logFile?.write(level, tag, message, error)
    }

    fun tail(maxChars: Int = 60_000): String = logFile?.tail(maxChars).orEmpty()

    fun clear() {
        logFile?.clear()
        i("App", "==== log cleared: $header ====")
    }

    /** Share-sheet intent with the whole log attached as one text file. */
    fun shareIntent(context: Context): Intent? {
        val file = logFile ?: return null
        val export = file.export(File(context.filesDir, "logs/andrip-log.txt"), header)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", export)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AndRip log")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AndRip log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share AndRip log")
    }

    private fun webViewVersion(): String = try {
        WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }
}
