package com.andrip.browser.download

import android.webkit.CookieManager
import com.andrip.browser.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The stream is fine, AndRip just can't (or won't) save it. The message is shown to the user. */
class UnsupportedStreamException(message: String) : IOException(message)

data class RequestHeaders(val referer: String?, val userAgent: String?)

/** Sends the browser's own cookies with every download request. */
private class WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw: String? = try {
            CookieManager.getInstance().getCookie(url.toString())
        } catch (_: Throwable) {
            null
        }
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class DownloadEngine(private val client: OkHttpClient = Http.client) {

    data class Output(val file: File, val extension: String)

    /** Plain file download. Resumes from [target]'s current size when the server allows it. */
    suspend fun downloadFile(
        url: String,
        headers: RequestHeaders,
        target: File,
        onProgress: (percent: Int, detail: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var existing = if (target.exists()) target.length() else 0L
        val range = if (existing > 0) "bytes=$existing-" else null
        client.newCall(request(url, headers, range)).execute().use { response ->
            if (response.code == 416 && existing > 0) {
                target.delete()
                throw IOException("Server refused to resume this download. Tap Retry to start over.")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val append = response.code == 206 && existing > 0
            if (!append) existing = 0L
            val body = response.body ?: throw IOException("Empty response for $url")
            val length = body.contentLength()
            val total = if (length >= 0) existing + length else -1L
            AppLog.d(TAG, "File: HTTP ${response.code}, total=$total, resumedFrom=$existing, type=${body.contentType()}")

            FileOutputStream(target, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = existing
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        onProgress(percent, if (total > 0) "${megabytes(done)} of ${megabytes(total)}" else megabytes(done))
                    }
                }
            }
        }
    }

    /**
     * HLS: picks the best quality, downloads the video (and the separate audio playlist when
     * there is one), then merges everything into a single MP4.
     */
    suspend fun downloadHls(
        playlistUrl: String,
        headers: RequestHeaders,
        workDir: File,
        maxHeight: Int? = null,
        onProgress: (percent: Int, detail: String) -> Unit,
    ): Output = withContext(Dispatchers.IO) {
        var (text, url) = fetchText(playlistUrl, headers)
        var playlist = HlsParser.parse(text, url)
        var audioUrl: String? = null

        if (playlist is HlsMaster) {
            if (playlist.protection == HlsProtection.DRM) throw drm()
            val variant = playlist.pick(maxHeight) ?: throw IOException("Playlist lists no streams: $url")
            AppLog.i(TAG, "HLS: ${playlist.variants.size} qualities, picked ${variant.label} (${variant.codecs ?: "?"}), separateAudio=${variant.separateAudio}")
            audioUrl = variant.audioUrl
            val fetched = fetchText(variant.url, headers)
            text = fetched.first
            url = fetched.second
            playlist = HlsParser.parse(text, url)
        }

        val video = checkedMedia(playlist, url)
        val plans = ArrayList<TrackPlan>()
        plans.add(TrackPlan("video", video.initSegment, video.segments))
        if (audioUrl != null) {
            val (audioText, finalAudioUrl) = fetchText(audioUrl, headers)
            val audio = checkedMedia(HlsParser.parse(audioText, finalAudioUrl), finalAudioUrl)
            plans.add(TrackPlan("audio", audio.initSegment, audio.segments))
        }
        AppLog.i(TAG, "HLS: " + plans.joinToString { "${it.name}=${it.segments.size} segments" } + ", ~${video.durationSeconds.toInt()}s")
        downloadAndMerge(plans, headers, workDir, if (video.initSegment != null) "mp4" else "ts", onProgress)
    }

    /** DASH: best video + best matching audio, merged into a single file. */
    suspend fun downloadDash(
        manifestUrl: String,
        headers: RequestHeaders,
        workDir: File,
        maxHeight: Int? = null,
        onProgress: (percent: Int, detail: String) -> Unit,
    ): Output = withContext(Dispatchers.IO) {
        val (text, url) = fetchText(manifestUrl, headers)
        val manifest = DashParser.parse(text, url)
        if (manifest.isLive) throw UnsupportedStreamException("This is a live stream. Only finished videos can be saved.")
        val video = manifest.bestVideo(maxHeight)
        val audio = manifest.bestAudio(video)
        val tracks = listOfNotNull(video, audio)
        if (tracks.isEmpty()) throw IOException("DASH manifest lists no audio or video: $url")
        if (tracks.any { it.isProtected }) throw drm()
        AppLog.i(
            TAG,
            "DASH: ${manifest.video.size} video / ${manifest.audio.size} audio qualities; picked " +
                tracks.joinToString { "${it.mimeType} ${it.codecs ?: "?"} ${it.height?.let { h -> "${h}p " } ?: ""}${it.bandwidth / 1000}kbps x${it.segments.size}" },
        )
        val plans = tracks.map { TrackPlan(if (it.isVideo) "video" else "audio", it.init, it.segments) }
        downloadAndMerge(plans, headers, workDir, if (tracks.first().isWebm) "webm" else "mp4", onProgress)
    }

    private class TrackPlan(val name: String, val init: MediaSegment?, val segments: List<MediaSegment>)

    private fun checkedMedia(playlist: HlsPlaylist, url: String): HlsMedia {
        val media = playlist as? HlsMedia ?: throw IOException("Unexpected nested playlist: $url")
        when (media.protection) {
            HlsProtection.DRM -> throw drm()
            HlsProtection.AES_128 -> throw UnsupportedStreamException("This stream is encrypted, which isn't supported.")
            HlsProtection.NONE -> Unit
        }
        if (media.isLive) throw UnsupportedStreamException("This is a live stream. Only finished videos can be saved.")
        if (media.segments.isEmpty()) throw IOException("Playlist has no segments: $url")
        return media
    }

    /** Downloads every track, then merges them into one file with sound. */
    private suspend fun downloadAndMerge(
        plans: List<TrackPlan>,
        headers: RequestHeaders,
        workDir: File,
        rawExtension: String,
        onProgress: (percent: Int, detail: String) -> Unit,
    ): Output = coroutineScope {
        workDir.mkdirs()
        val total = plans.sumOf { it.segments.size }
        val finished = AtomicInteger(0)
        val files = ArrayList<File>()
        for (plan in plans) {
            files.add(
                downloadTrack(plan, headers, workDir) {
                    val count = finished.incrementAndGet()
                    onProgress(count * 100 / total, "$count of $total segments")
                },
            )
        }

        onProgress(-1, if (files.size > 1) "Merging audio and video…" else "Finishing up…")
        ensureActive()
        try {
            val merged = Remuxer.mux(files, workDir)
            files.forEach { it.delete() }
            Output(merged, merged.extension)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (files.size > 1) throw IOException("Could not merge the audio and video tracks: ${e.message}", e)
            // One self-contained stream: the joined download is still playable as it is.
            AppLog.w(TAG, "Could not convert to MP4; saving the stream as downloaded (.$rawExtension)", e)
            Output(files[0], rawExtension)
        }
    }

    /** Fetches one track's segments (four at a time) and joins them, in order, into a single file. */
    private suspend fun downloadTrack(
        plan: TrackPlan,
        headers: RequestHeaders,
        workDir: File,
        onSegmentDone: () -> Unit,
    ): File = coroutineScope {
        val dir = File(workDir, plan.name).apply { mkdirs() }
        val gate = Semaphore(PARALLEL_SEGMENTS)
        plan.segments.mapIndexed { index, segment ->
            async {
                gate.withPermit {
                    ensureActive()
                    val part = partFile(dir, index)
                    if (!part.exists()) { // already there when a failed download is retried
                        val temp = File(dir, part.name + ".tmp")
                        fetchSegment(segment, headers, temp)
                        if (!temp.renameTo(part)) throw IOException("Could not store ${plan.name} segment $index")
                    }
                    onSegmentDone()
                }
            }
        }.awaitAll()

        val joined = File(workDir, plan.name + ".bin")
        FileOutputStream(joined).use { out ->
            plan.init?.let { init ->
                val initFile = File(dir, "init.tmp")
                fetchSegment(init, headers, initFile)
                copyInto(initFile, out)
            }
            for (index in plan.segments.indices) {
                ensureActive()
                copyInto(partFile(dir, index), out)
            }
        }
        dir.deleteRecursively()
        joined
    }

    private fun drm() = UnsupportedStreamException("This stream is DRM-protected and can't be downloaded.")

    private fun partFile(dir: File, index: Int) = File(dir, String.format(Locale.US, "seg-%05d.part", index))

    private fun copyInto(source: File, out: OutputStream) {
        source.inputStream().use { it.copyTo(out, 256 * 1024) }
    }

    /** @return the body and the URL it finally came from (after redirects), for resolving relative links. */
    private fun fetchText(url: String, headers: RequestHeaders): Pair<String, String> =
        client.newCall(request(url, headers, null)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for manifest $url")
            val body = response.body?.string() ?: throw IOException("Empty manifest: $url")
            body to response.request.url.toString()
        }

    private fun fetchSegment(segment: MediaSegment, headers: RequestHeaders, target: File) {
        val start = segment.rangeStart
        val length = segment.rangeLength
        val range = if (start != null && length != null) "bytes=$start-${start + length - 1}" else null
        var lastError: IOException? = null
        repeat(SEGMENT_ATTEMPTS) { attempt ->
            try {
                client.newCall(request(segment.url, headers, range)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} for segment ${segment.url}")
                    val body = response.body ?: throw IOException("Empty segment ${segment.url}")
                    FileOutputStream(target).use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
                }
                return
            } catch (e: IOException) {
                lastError = e
                AppLog.w(TAG, "Segment attempt ${attempt + 1}/$SEGMENT_ATTEMPTS failed: ${e.message}")
            }
        }
        throw lastError ?: IOException("Segment failed: ${segment.url}")
    }

    private fun request(url: String, headers: RequestHeaders, range: String?): Request {
        val builder = Request.Builder().url(url)
        setHeader(builder, "User-Agent", headers.userAgent)
        setHeader(builder, "Referer", headers.referer)
        setHeader(builder, "Range", range)
        return builder.build()
    }

    private fun setHeader(builder: Request.Builder, name: String, value: String?) {
        if (value.isNullOrEmpty()) return
        try {
            builder.header(name, value)
        } catch (_: IllegalArgumentException) {
            // OkHttp rejects non-ASCII header values (e.g. a referer with unicode in it); skip it.
        }
    }

    private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

    private companion object {
        const val TAG = "Engine"
        const val PARALLEL_SEGMENTS = 4
        const val SEGMENT_ATTEMPTS = 3
    }
}
