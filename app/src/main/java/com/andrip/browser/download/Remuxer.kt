package com.andrip.browser.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.andrip.browser.log.AppLog
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Combines downloaded streams into one playable file without re-encoding.
 *
 * Takes one file (video with its audio already inside) or two (video + separate audio),
 * copies the first video track and the first audio track it finds into a single MP4
 * (or WebM when the codecs are VP8/VP9/Opus/Vorbis), interleaved by timestamp so the
 * result plays with sound in sync.
 *
 * Uses only Android's own MediaExtractor / MediaMuxer.
 */
object Remuxer {
    private const val TAG = "Remux"
    private const val MIN_BUFFER = 4 * 1024 * 1024
    private val webmMimes = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "audio/vorbis", "audio/opus")

    private class Source(val extractor: MediaExtractor, val format: MediaFormat, val mime: String, val isVideo: Boolean) {
        var muxerTrack = -1
        var lastTimeUs = -1L
        var samples = 0L
        var done = false
    }

    /** @return the merged file, created inside [outputDir] as final.mp4 or final.webm. */
    fun mux(inputs: List<File>, outputDir: File): File {
        val sources = ArrayList<Source>()
        try {
            var haveVideo = false
            var haveAudio = false
            for (input in inputs) {
                val probe = MediaExtractor()
                var probeUsed = false
                try {
                    probe.setDataSource(input.path)
                    for (index in 0 until probe.trackCount) {
                        val format = probe.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val isVideo = mime.startsWith("video/")
                        val isAudio = mime.startsWith("audio/")
                        if ((isVideo && !haveVideo) || (isAudio && !haveAudio)) {
                            // One extractor per track, so each track can be read at its own pace.
                            val extractor = if (!probeUsed) probe else MediaExtractor().apply { setDataSource(input.path) }
                            probeUsed = true
                            extractor.selectTrack(index)
                            sources.add(Source(extractor, format, mime, isVideo))
                            if (isVideo) haveVideo = true else haveAudio = true
                        }
                    }
                } finally {
                    if (!probeUsed) probe.release()
                }
            }
            if (sources.isEmpty()) throw IOException("No audio or video tracks found in the downloaded data")
            AppLog.i(TAG, "Tracks: " + sources.joinToString { it.mime } + " from ${inputs.size} file(s)")

            val webm = sources.all { it.mime in webmMimes }
            val output = File(outputDir, if (webm) "final.webm" else "final.mp4")
            output.delete()
            val muxer = MediaMuxer(
                output.path,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            try {
                var bufferSize = MIN_BUFFER
                for (source in sources) {
                    source.muxerTrack = muxer.addTrack(source.format)
                    if (source.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = maxOf(bufferSize, source.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                muxer.start()

                val buffer = ByteBuffer.allocateDirect(bufferSize)
                val info = MediaCodec.BufferInfo()
                // Streams rarely start at zero; shift everything so the file does, keeping A/V in sync.
                val startUs = sources.map { it.extractor.sampleTime }.filter { it >= 0 }.minOrNull() ?: 0L

                while (true) {
                    var next: Source? = null
                    for (source in sources) {
                        if (source.done) continue
                        val time = source.extractor.sampleTime
                        if (time < 0) {
                            source.done = true
                            continue
                        }
                        if (next == null || time < next.extractor.sampleTime) next = source
                    }
                    val source = next ?: break

                    val size = source.extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        source.done = true
                        continue
                    }
                    val timeUs = (source.extractor.sampleTime - startUs).coerceAtLeast(0L)
                    // Audio timestamps must only go forward or the muxer rejects the file.
                    if (source.isVideo || timeUs > source.lastTimeUs) {
                        val keyFrame = (source.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        info.set(0, size, timeUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(source.muxerTrack, buffer, info)
                        source.lastTimeUs = maxOf(source.lastTimeUs, timeUs)
                        source.samples++
                    }
                    if (!source.extractor.advance()) source.done = true
                }
                muxer.stop()
            } finally {
                try {
                    muxer.release()
                } catch (_: Exception) {
                }
            }

            for (source in sources) {
                AppLog.i(TAG, "${source.mime}: ${source.samples} samples, ${source.lastTimeUs / 1_000_000}s")
                if (source.samples == 0L) throw IOException("The ${if (source.isVideo) "video" else "audio"} track came out empty")
            }
            return output
        } finally {
            for (source in sources) {
                try {
                    source.extractor.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
