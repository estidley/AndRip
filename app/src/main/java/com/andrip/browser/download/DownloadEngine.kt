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
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One shared connection pool; browser cookies are resolved separately for every redirected URL. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(ContextInterceptor(object : BrowserCookies {
                override fun get(url: String): String? = CookieManager.getInstance().getCookie(url)
                override fun set(url: String, cookie: String) { CookieManager.getInstance().setCookie(url, cookie) }
            }))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class DownloadEngine(private val client: OkHttpClient = Http.client) {

    data class Output(val file: File, val extension: String, val completedSegments: Int? = null)

    private val transfer = MediaTransfer(client)

    suspend fun downloadFile(url: String, headers: RequestHeaders, target: File,
        onProgress: (Int, String) -> Unit) = transfer.file(url, headers, target, onProgress)

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
        if (manifest.audio.isNotEmpty() && audio == null) throw UnsupportedStreamException("No audio track can share this video's export container. Try another source format.")
        val tracks = listOfNotNull(video, audio)
        if (tracks.isEmpty()) throw IOException("DASH manifest lists no audio or video: $url")
        if ((manifest.video + manifest.audio).any { it.isProtected }) throw drm()
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
        val progress = SegmentProgress(total, onProgress)
        progress.start()
        val files = ArrayList<File>()
        for (plan in plans) {
            files.add(
                downloadTrack(plan, headers, workDir) {
                    progress.segmentDone()
                },
            )
        }

        progress.finishing(if (files.size > 1) "Merging audio and video…" else "Finishing up…")
        ensureActive()
        files.forEach { MediaProtection.checkFile(it) }
        StorageSpace.requireSpace(workDir, files.sumOf { it.length() })
        try {
            val merged = Remuxer.mux(files, workDir)
            files.forEach { it.delete() }
            Output(merged, merged.extension, total)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedStreamException) {
            throw e
        } catch (e: Exception) {
            if (files.size > 1) throw IOException("Could not merge the audio and video tracks: ${e.message}", e)
            if (rawExtension != "ts") throw IOException("This media could not be finalized on this device. Try another quality.", e)
            // A single TS stream can be saved without changing its container.
            AppLog.w(TAG, "Could not convert to MP4; saving the stream as downloaded (.$rawExtension)", e)
            Output(files[0], rawExtension, total)
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
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest((plan.init.toString() + plan.segments.joinToString()).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val identity = File(dir, "identity")
        if (!identity.exists() || identity.readText() != fingerprint) {
            dir.deleteRecursively()
            dir.mkdirs()
            identity.writeText(fingerprint)
        }
        val gate = Semaphore(PARALLEL_SEGMENTS)
        // Bound both the number of connections and allocated coroutine jobs.
        plan.segments.withIndex().chunked(PARALLEL_SEGMENTS).forEach { batch ->
        batch.map { (index, segment) ->
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
        }

        val joined = File(workDir, plan.name + ".bin")
        StorageSpace.requireSpace(workDir, plan.segments.indices.sumOf { partFile(dir, it).length() })
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
        joined
    }

    private fun drm() = UnsupportedStreamException("This stream is DRM-protected and can't be downloaded.")

    private fun partFile(dir: File, index: Int) = File(dir, String.format(Locale.US, "seg-%05d.part", index))

    private suspend fun copyInto(source: File, out: OutputStream) {
        source.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        }
    }

    private suspend fun fetchText(url: String, headers: RequestHeaders) = transfer.text(url, headers)

    private suspend fun fetchSegment(segment: MediaSegment, headers: RequestHeaders, target: File) =
        transfer.segment(segment, headers, target)

    private companion object {
        const val TAG = "Engine"
        const val PARALLEL_SEGMENTS = 4
    }
}
