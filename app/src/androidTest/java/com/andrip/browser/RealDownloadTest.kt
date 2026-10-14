package com.andrip.browser

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.andrip.browser.data.AppDatabase
import com.andrip.browser.data.DownloadStatus
import com.andrip.browser.detect.DetectedMedia
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.download.DownloadQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real WorkManager, foreground service, sockets, notifications, extractor and device storage. */
@RunWith(AndroidJUnit4::class)
class RealDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dao = AppDatabase.get(context).downloads()
    private val notices = context.getSystemService(NotificationManager::class.java)
    private val ids = mutableListOf<Long>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var server: MockWebServer
    private var retrySegment = AtomicBoolean(false)
    private fun fixture(path: String) = instrumentation.context.assets.open("media/$path").use { it.readBytes() }

    @Before fun start() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("am start -W -a android.intent.action.VIEW -d http://127.0.0.1:9/ -n ${context.packageName}/.MainActivity")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.requestUrl!!.encodedPath.removePrefix("/")
                if (path == "denied.mp4") return MockResponse().setResponseCode(403)
                if (path == "redirect.mp4") return MockResponse().setResponseCode(302)
                    .setHeader("Location", "/movie.mp4").setHeader("Set-Cookie", "fixture_session=allowed; Path=/")
                if (path == "movie.mp4" && (request.getHeader("Cookie")?.contains("fixture_session=allowed") != true ||
                    request.getHeader("Referer") != server.url("/watch").toString() || request.getHeader("User-Agent") != "AndRip-device-test")) {
                    return MockResponse().setResponseCode(403)
                }
                val bytes = try { fixture(path) } catch (_: Exception) { return MockResponse().setResponseCode(404) }
                if ((path.endsWith(".ts") || path.endsWith(".m4s")) && retrySegment.compareAndSet(true, false)) {
                    return MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                }
                val offset = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val response = MockResponse().setHeader("ETag", "\"fixture-v1\"")
                    .setHeader("Content-Type", when {
                        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                        path.endsWith(".mpd") -> "application/dash+xml"
                        path.endsWith(".ts") -> "video/mp2t"
                        else -> "video/mp4"
                    }).setBody(Buffer().write(bytes, offset, bytes.size - offset))
                if (offset > 0) response.setResponseCode(206).setHeader("Content-Range", "bytes $offset-${bytes.size - 1}/${bytes.size}")
                if (path == "movie.mp4") response.throttleBody(32 * 1024, 250, TimeUnit.MILLISECONDS)
                if (path.endsWith(".ts") || path.endsWith(".m4s")) response.setBodyDelay(2, TimeUnit.SECONDS)
                return response
            }
        }
        server.start()
    }

    @After fun stop() = runBlocking {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        for (id in ids) {
            val uri = dao.get(id)?.outputUri
            DownloadQueue.remove(context, id)
            if (uri != null) runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
        }
        server.shutdown()
    }

    private suspend fun enqueue(path: String, kind: MediaKind): Long {
        val item = DetectedMedia(server.url(path).toString(), kind, server.url("/watch").toString(),
            "Device test ${kind.name}", server.url("/watch").toString(), "AndRip-device-test", "test")
        return DownloadQueue.enqueue(context, item).also { ids.add(it) }
    }
    private suspend fun awaitCondition(description: String, test: suspend () -> Boolean) {
        try { withTimeout(120_000) { while (!test()) delay(200) } }
        catch (e: Exception) { throw AssertionError("$description; rows=${ids.map { dao.get(it) }}", e) }
    }
    private fun progress(id: Long) = notices.activeNotifications.firstOrNull { it.id == 4000 + (id % 100_000).toInt() }?.notification
    private fun completed(id: Long) = notices.activeNotifications.firstOrNull { it.tag == "download-result-$id" }?.notification
    private fun text(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    private fun screenshot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "download-test-evidence").apply { mkdirs() }
        device.takeScreenshot(File(folder, "$name.png"))
    }
    private suspend fun running(id: Long) {
        awaitCondition("Visible foreground progress for $id") {
            val n = progress(id)
            check(dao.get(id)?.status != DownloadStatus.FAILED) { dao.get(id)?.error.orEmpty() }
            n != null && n.extras.getInt(Notification.EXTRA_PROGRESS) in 1..95
        }
        assertTrue(progress(id)!!.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals(100, progress(id)!!.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        device.pressHome()
        assertFalse("App must be in background", device.currentPackageName == context.packageName)
    }
    private suspend fun finish(id: Long, segmented: Boolean) {
        awaitCondition("Saved output and completion notification for $id") {
            check(dao.get(id)?.status != DownloadStatus.FAILED) { dao.get(id)?.error.orEmpty() }
            dao.get(id)?.status == DownloadStatus.DONE && completed(id) != null && progress(id) == null
        }
        assertEquals("Download complete", completed(id)!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(0, completed(id)!!.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(segmented, text(completed(id)).contains("segments saved"))
        verifyMedia(Uri.parse(dao.get(id)!!.outputUri))
    }
    private fun verifyMedia(uri: Uri) {
        // Read every sample and seek to the midpoint; a nonempty file alone is not a playable export.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }
            assertTrue("Missing video: $mimes", mimes.any { it.startsWith("video/") })
            assertTrue("Missing audio: $mimes", mimes.any { it.startsWith("audio/") })
            for (track in 0 until extractor.trackCount) {
                extractor.selectTrack(track)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                var count = 0
                var lastTime = 0L
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    lastTime = maxOf(lastTime, extractor.sampleTime)
                    count++
                    if (!extractor.advance()) break
                }
                assertTrue("Too few samples on $track: $count (${mimes[track]})", count > 100)
                assertTrue("Truncated track $track: $lastTime", lastTime > 22_000_000)
                extractor.seekTo(12_000_000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                assertTrue("Seek failed on $track", extractor.readSampleData(buffer, 0) > 0)
                assertTrue(extractor.sampleTime in 10_000_000..14_000_000)
                extractor.unselectTrack(track)
            }
        } finally { extractor.release() }
    }

    @Test fun progressiveBackgroundPauseResumeScreenOffAndExactBytes() = runBlocking {
        val id = enqueue("/redirect.mp4", MediaKind.FILE)
        running(id)
        val before = progress(id)!!.extras.getInt(Notification.EXTRA_PROGRESS)
        awaitCondition("Progress continues after Home") { (progress(id)?.extras?.getInt(Notification.EXTRA_PROGRESS) ?: 0) > before }
        device.openNotification(); delay(500); screenshot("progressive-progress"); device.pressHome()
        progress(id)!!.actions.first { it.title.toString() == "Pause" }.actionIntent.send()
        awaitCondition("Notification pause stops the service") { dao.get(id)?.status == DownloadStatus.PAUSED && progress(id) == null }
        assertNull(completed(id))
        val requestCount = requests.size
        delay(2000)
        assertEquals("Pause should stop new network requests", requestCount, requests.size)
        device.executeShellCommand("am start -W -n ${context.packageName}/.MainActivity")
        DownloadQueue.retry(context, id)
        running(id)
        device.sleep()
        finish(id, false)
        assertFalse("Download finished with screen off", device.isScreenOn)
        val bytes = context.contentResolver.openInputStream(Uri.parse(dao.get(id)!!.outputUri))!!.use { it.readBytes() }
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(fixture("movie.mp4")), MessageDigest.getInstance("SHA-256").digest(bytes))
        assertTrue("Resume must use Range and If-Range", requests.any { it.getHeader("Range") != null && it.getHeader("If-Range") == "\"fixture-v1\"" })
        device.wakeUp(); device.openNotification(); delay(500); screenshot("progressive-completed")
    }

    private suspend fun segmented(path: String, kind: MediaKind) {
        retrySegment.set(true)
        val id = enqueue(path, kind)
        running(id)
        assertTrue(text(progress(id)).contains("segments"))
        val before = progress(id)!!.extras.getInt(Notification.EXTRA_PROGRESS)
        device.openNotification(); delay(500); screenshot("${kind.name}-progress"); device.pressHome()
        device.sleep()
        awaitCondition("Segment progress continues with screen off") { (progress(id)?.extras?.getInt(Notification.EXTRA_PROGRESS) ?: 0) > before }
        finish(id, true)
        if (kind == MediaKind.HLS && dao.get(id)!!.fileName!!.endsWith(".ts")) {
            val expected = (0 until 12).fold(Buffer()) { buffer, index -> buffer.write(fixture("hls/seg%03d.ts".format(java.util.Locale.US, index))) }.readByteArray()
            val saved = context.contentResolver.openInputStream(Uri.parse(dao.get(id)!!.outputUri))!!.use { it.readBytes() }
            assertArrayEquals("TS fallback must preserve every original byte", expected, saved)
        }
        assertTrue("Transient segment error was retried", requests.groupBy { it.path }.any { (path, requests) -> (path!!.endsWith(".ts") || path.endsWith(".m4s")) && requests.size > 1 })
        device.wakeUp(); device.openNotification(); delay(500); screenshot("${kind.name}-completed")
    }
    @Test fun hlsBackgroundSegmentsRetryAndPlayableExport() = runBlocking { segmented("/hls/media.m3u8", MediaKind.HLS) }
    @Test fun dashBackgroundSeparateAudioVideoAndPlayableExport() = runBlocking { segmented("/dash/media.mpd", MediaKind.DASH) }

    @Test fun notificationCancelStopsTransferWithoutCompletion() = runBlocking {
        val id = enqueue("/redirect.mp4", MediaKind.FILE)
        running(id)
        progress(id)!!.actions.first { it.title.toString() == "Cancel" }.actionIntent.send()
        awaitCondition("Cancel stops foreground work") { dao.get(id)?.status == DownloadStatus.CANCELED && progress(id) == null }
        delay(1500)
        assertNull(dao.get(id)!!.outputUri)
        assertNull(completed(id))
    }

    @Test fun accessDeniedHasActionableErrorAndNoFalseCompletion() = runBlocking {
        val id = enqueue("/denied.mp4", MediaKind.FILE)
        awaitCondition("403 failure notification") { dao.get(id)?.status == DownloadStatus.FAILED && completed(id) != null && progress(id) == null }
        assertTrue(dao.get(id)!!.error!!.contains("fresh link"))
        assertNotEquals("Download complete", completed(id)!!.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNull(dao.get(id)!!.outputUri)
        assertEquals(1, requests.size)
    }
}
