package com.andrip.browser.download

import android.webkit.CookieManager
import com.andrip.browser.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

class DownloadEngine(
    private val client: OkHttpClient = Http.client,
    private val inspect: (File) -> Unit = MediaProtection::checkFile,
    private val remux: suspend (List<File>, File) -> File = { inputs, dir -> Remuxer.mux(inputs, dir) },
) {

    data class Output(
        val file: File,
        val extension: String,
        val completedSegments: Int? = null,
        val sidecar: File? = null,
    )

    private val transfer = MediaTransfer(client)
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val keyLock = Mutex()

    suspend fun downloadFile(url: String, headers: RequestHeaders, target: File,
        onProgress: (Int, String) -> Unit) = transfer.file(url, headers, target, onProgress)

    /**
     * HLS: picks the best quality, downloads the video (and a separate audio playlist when
     * the video does not already contain audio), then merges everything into a single file.
     * AES-128 segments are decrypted with the key the playlist itself serves. A live playlist
     * saves the window it lists right now.
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
        var subtitles = emptyList<HlsRendition>()
        var hops = 0

        while (playlist is HlsMaster) {
            if (++hops > 4) throw IOException("HLS playlist nesting is too deep")
            if (playlist.protection == HlsProtection.DRM) throw drm()
            if (subtitles.isEmpty()) subtitles = playlist.subtitles
            val variant = playlist.pick(maxHeight) ?: throw IOException("Playlist lists no streams: $url")
            if (audioUrl == null && variant.fetchSeparateAudio()) audioUrl = variant.audioUrl
            AppLog.i(TAG, "HLS: ${playlist.variants.size} qualities, picked ${variant.label} (${variant.codecs ?: "?"}), separateAudio=${variant.fetchSeparateAudio()}")
            val fetched = fetchText(variant.url, headers)
            text = fetched.first
            url = fetched.second
            playlist = HlsParser.parse(text, url)
        }

        val video = checkedMedia(playlist, url)
        val plans = ArrayList<TrackPlan>()
        plans.add(TrackPlan("video", url, video.initSegment, video.segments.toMutableList()))
        var live = video.isLive
        if (audioUrl != null) {
            val (audioText, finalAudioUrl) = fetchText(audioUrl, headers)
            val audio = checkedMedia(HlsParser.parse(audioText, finalAudioUrl), finalAudioUrl)
            live = live || audio.isLive
            plans.add(TrackPlan("audio", finalAudioUrl, audio.initSegment, audio.segments.toMutableList()))
        }
        if (live) AppLog.i(TAG, "HLS live window: saving the ${video.segments.size} segments listed now")
        AppLog.i(TAG, "HLS: " + plans.joinToString { "${it.name}=${it.segments.size} segments" } + ", ~${video.durationSeconds.toInt()}s")
        val sidecar = trySubtitles(subtitles.map { it.url }, headers, workDir)
        downloadAndMerge(plans, headers, workDir, if (video.initSegment != null) "mp4" else "ts", onProgress, sidecar, if (live) "live window" else "")
    }

    /** DASH: best video + best matching audio, merged into a single file. A dynamic MPD saves its current window. */
    suspend fun downloadDash(
        manifestUrl: String,
        headers: RequestHeaders,
        workDir: File,
        maxHeight: Int? = null,
        onProgress: (percent: Int, detail: String) -> Unit,
    ): Output = withContext(Dispatchers.IO) {
        val (text, url) = fetchText(manifestUrl, headers)
        val manifest = DashParser.parse(text, url)
        val video = manifest.bestVideo(maxHeight)
        val audio = manifest.bestAudio(video)
        if (manifest.audio.isNotEmpty() && audio == null) throw UnsupportedStreamException("No audio track can share this video's export container. Try another source format.")
        val tracks = listOfNotNull(video, audio)
        if (tracks.isEmpty()) throw IOException("DASH manifest lists no audio or video: $url")
        if ((manifest.video + manifest.audio).any { it.isProtected }) throw drm()
        if (manifest.isLive) AppLog.i(TAG, "DASH live window: saving the segments currently listed")
        AppLog.i(
            TAG,
            "DASH: ${manifest.video.size} video / ${manifest.audio.size} audio qualities; picked " +
                tracks.joinToString { "${it.mimeType} ${it.codecs ?: "?"} ${it.height?.let { h -> "${h}p " } ?: ""}${it.bandwidth / 1000}kbps x${it.segments.size}" },
        )
        val plans = tracks.map { TrackPlan(if (it.isVideo) "video" else "audio", null, it.init, it.segments.toMutableList()) }
        val sidecar = trySubtitles(manifest.subtitles, headers, workDir)
        downloadAndMerge(plans, headers, workDir, if (tracks.first().isWebm) "webm" else "mp4", onProgress, sidecar, if (manifest.isLive) "live window" else "")
    }

    private class TrackPlan(
        val name: String,
        val playlistUrl: String?,
        var init: MediaSegment?,
        val segments: MutableList<MediaSegment>,
    )

    private fun checkedMedia(playlist: HlsPlaylist, url: String): HlsMedia {
        val media = playlist as? HlsMedia ?: throw IOException("Unexpected nested playlist: $url")
        when (media.protection) {
            HlsProtection.DRM -> throw drm()
            HlsProtection.SAMPLE_AES -> throw UnsupportedStreamException("This stream uses sample encryption (SAMPLE-AES). Saving it isn't supported yet.")
            HlsProtection.AES_128, HlsProtection.NONE -> Unit
        }
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
        sidecar: File?,
        note: String,
    ): Output = coroutineScope {
        workDir.mkdirs()
        val total = plans.sumOf { it.segments.size }
        val progress = SegmentProgress(total, note, onProgress)
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
        files.forEach { inspect(it) }
        // Native TS extraction may expose grouped AAC access units. Passing those straight
        // to MediaMuxer creates an MP4 with incomplete audio. Keep the clear transport stream
        // intact until we have a demuxer that can reliably split and timestamp every AAC frame.
        if (rawExtension == "ts") {
            if (files.size == 1) return@coroutineScope Output(files[0], "ts", total, sidecar)
            throw UnsupportedStreamException("Separate TS audio and video cannot be merged safely. Choose an fMP4 HLS or DASH source instead.")
        }
        StorageSpace.requireSpace(workDir, files.sumOf { it.length() })
        try {
            val merged = remux(files, workDir)
            files.forEach { it.delete() }
            Output(merged, merged.extension, total, sidecar)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedStreamException) {
            throw e
        } catch (e: Exception) {
            if (files.size > 1) throw MediaFinalizationException("Could not merge the audio and video tracks: ${e.message}. Try another source format or quality.", e)
            throw MediaFinalizationException("This media could not be finalized on this device. Try another quality.", e)
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
        val refreshLock = Mutex()
        var refreshes = 0
        suspend fun fetchAt(index: Int?, target: File) {
            fun current() = if (index == null) {
                plan.init ?: throw IOException("Missing initialization segment")
            } else {
                plan.segments[index]
            }
            try {
                fetchSegment(current(), headers, target, dir)
            } catch (e: HttpFailure) {
                if (e.code !in REFRESH_CODES || plan.playlistUrl == null) throw e
                refreshLock.withLock {
                    if (refreshes == 0) {
                        reload(plan, headers)
                        refreshes = 1
                    }
                }
                target.delete()
                fetchSegment(current(), headers, target, dir)
            }
        }
        // Bound both the number of connections and allocated coroutine jobs.
        plan.segments.indices.chunked(PARALLEL_SEGMENTS).forEach { batch ->
            batch.map { index ->
                async {
                    gate.withPermit {
                        ensureActive()
                        val part = partFile(dir, index)
                        if (!part.exists()) { // already there when a failed download is retried
                            val temp = File(dir, part.name + ".tmp")
                            fetchAt(index, temp)
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
            plan.init?.let {
                val initFile = File(dir, "init.bin")
                fetchAt(null, initFile)
                copyInto(initFile, out)
            }
            for (index in plan.segments.indices) {
                ensureActive()
                copyInto(partFile(dir, index), out)
            }
        }
        joined
    }

    private suspend fun reload(plan: TrackPlan, headers: RequestHeaders) {
        val playlistUrl = plan.playlistUrl ?: return
        AppLog.i(TAG, "Refreshing ${plan.name} playlist after an expired or rejected segment URL")
        val (text, finalUrl) = fetchText(playlistUrl, headers)
        val media = checkedMedia(HlsParser.parse(text, finalUrl), finalUrl)
        if (media.segments.size != plan.segments.size || plan.segments.indices.any { index ->
                plan.segments[index].url.substringBefore('?') != media.segments[index].url.substringBefore('?') ||
                    plan.segments[index].rangeStart != media.segments[index].rangeStart ||
                    plan.segments[index].rangeLength != media.segments[index].rangeLength
            }
        ) {
            throw IOException("The playlist changed shape while refreshing an expired link. Reopen the page and save again.")
        }
        for (index in plan.segments.indices) plan.segments[index] = media.segments[index]
        if (plan.init != null && media.initSegment != null) plan.init = media.initSegment
    }

    private suspend fun fetchSegment(segment: MediaSegment, headers: RequestHeaders, target: File, dir: File) {
        val keyUrl = segment.keyUrl
        if (keyUrl == null) {
            transfer.segment(segment, headers, target)
            return
        }
        val encrypted = File(target.path + ".enc")
        try {
            transfer.segment(segment, headers, encrypted)
            val key = keyFor(keyUrl, headers, dir)
            val iv = segment.iv ?: throw IOException("Encrypted HLS segment has no IV")
            HlsAes.decryptFile(encrypted, target, key, iv)
        } finally {
            encrypted.delete()
        }
    }

    private suspend fun keyFor(url: String, headers: RequestHeaders, dir: File): ByteArray {
        keys[url]?.let { return it }
        return keyLock.withLock {
            keys[url]?.let { return@withLock it }
            val file = File(dir, "key.bin")
            transfer.segment(MediaSegment(HlsParser.requireHttp(url)), headers, file)
            val bytes = file.readBytes()
            file.delete()
            if (bytes.size != 16) throw IOException("HLS key must be 16 bytes")
            keys[url] = bytes
            bytes
        }
    }

    private suspend fun trySubtitles(urls: List<String>, headers: RequestHeaders, workDir: File): File? {
        val url = urls.firstOrNull() ?: return null
        return try {
            val (text, finalUrl) = fetchText(url, headers)
            val trimmed = text.trimStart()
            when {
                trimmed.startsWith("WEBVTT") -> File(workDir, "subtitles.vtt").apply { writeText(text) }
                trimmed.startsWith("<tt") || trimmed.contains("<tt ") -> File(workDir, "subtitles.ttml").apply { writeText(text) }
                trimmed.startsWith("#EXTM3U") -> concatVtt(text, finalUrl, headers, workDir)
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Subtitles were not saved", e)
            null
        }
    }

    private suspend fun concatVtt(text: String, url: String, headers: RequestHeaders, workDir: File): File? {
        val media = HlsParser.parse(text, url) as? HlsMedia ?: return null
        if (media.protection != HlsProtection.NONE || media.segments.isEmpty() || media.segments.size > 5_000) return null
        val out = File(workDir, "subtitles.vtt")
        out.outputStream().buffered().use { output ->
            media.segments.forEachIndexed { index, segment ->
                if (out.length() > 8 * 1024 * 1024) return null
                val part = File(workDir, "subtitle-part.tmp")
                transfer.segment(segment.copy(keyUrl = null, iv = null), headers, part)
                val body = part.readText()
                part.delete()
                if (index == 0) output.write(body.toByteArray())
                else output.write((if (body.startsWith("WEBVTT")) body.substringAfter("\n\n", body) else body).toByteArray())
            }
        }
        return out.takeIf { it.length() > 0L && it.length() <= 8 * 1024 * 1024 }
    }

    private fun drm() = UnsupportedStreamException(MediaProtection.PROTECTED)

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

    private companion object {
        const val TAG = "Engine"
        const val PARALLEL_SEGMENTS = 4
        val REFRESH_CODES = setOf(401, 403, 404)
    }
}
