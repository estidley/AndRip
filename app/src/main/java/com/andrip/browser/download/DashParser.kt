package com.andrip.browser.download

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/** One quality of one track (video or audio) in a DASH manifest, with its full segment list. */
data class DashTrack(
    val id: String,
    val isVideo: Boolean,
    val mimeType: String,
    val codecs: String?,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val isProtected: Boolean,
    val init: MediaSegment?,
    val segments: List<MediaSegment>,
) {
    val isWebm: Boolean get() = mimeType.contains("webm")

    /** Codecs Android can put into an MP4 without re-encoding. */
    val mp4Friendly: Boolean
        get() {
            val c = codecs?.lowercase().orEmpty()
            return !isWebm && (c.isEmpty() || c.startsWith("avc") || c.startsWith("hvc") || c.startsWith("hev") || c.startsWith("mp4a"))
        }
}

data class DashManifest(
    val video: List<DashTrack>,
    val audio: List<DashTrack>,
    val isLive: Boolean,
    val subtitles: List<String> = emptyList(),
) {

    fun bestVideo(maxHeight: Int? = null): DashTrack? {
        val sorted = video.sortedWith(
            compareByDescending<DashTrack> { it.mp4Friendly }
                .thenByDescending { it.height ?: 0 }
                .thenByDescending { it.bandwidth },
        )
        if (maxHeight == null) return sorted.firstOrNull()
        return sorted.firstOrNull { (it.height ?: 0) <= maxHeight } ?: sorted.lastOrNull()
    }

    /** Best audio that can share a container with [video]. */
    fun bestAudio(video: DashTrack?): DashTrack? {
        val wantWebm = video?.isWebm ?: false
        return audio.sortedWith(
            compareByDescending<DashTrack> { it.isWebm == wantWebm }
                .thenByDescending { it.mp4Friendly }
                .thenByDescending { it.bandwidth },
        ).firstOrNull { video == null || it.isWebm == wantWebm }
    }
}

/**
 * MPD parser for on-demand DASH: SegmentTemplate (number or timeline based),
 * SegmentList, and single-file representations. Multi-period exports are explicitly refused.
 */
object DashParser {
    private const val MAX_SEGMENTS = 100_000
    private class Budget {
        var count = 0
        var chars = 0L
        fun take(segment: MediaSegment): MediaSegment {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            count++
            chars += segment.url.length
            if (count > MAX_SEGMENTS || chars > 8_000_000) throw UnsupportedStreamException("DASH segment plan is too large to save safely.")
            return segment
        }
    }
    private val placeholder = Regex("\\$(RepresentationID|Number|Bandwidth|Time)(?:%0(\\d+)d)?\\$")
    private val isoDuration = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?")

    private class TimelineEntry(val start: Long?, val duration: Long, val repeat: Int)

    private class Template(
        val media: String?,
        val initialization: String?,
        val startNumber: Long,
        val timescale: Long,
        val duration: Long?,
        val timeline: List<TimelineEntry>?,
    )

    fun parse(xml: String, manifestUrl: String): DashManifest {
        if (xml.length > 4_000_000) throw UnsupportedStreamException("DASH manifest is too large.")
        val budget = Budget()
        if (Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(xml)) throw IOException("DASH manifests cannot contain document types or entities")
        val mpd = try {
            val factory = DocumentBuilderFactory.newInstance()
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
                // Not every parser knows this switch; manifests don't need doctypes either way.
            }
            factory.newDocumentBuilder()
                .parse(InputSource(StringReader(xml.removePrefix("\uFEFF").trim())))
                .documentElement
        } catch (e: Exception) {
            throw IOException("Not a readable DASH manifest: $manifestUrl", e)
        }
        if (mpd.localTag() != "MPD") throw IOException("Not a DASH manifest (root is <${mpd.tagName}>): $manifestUrl")

        val isLive = mpd.attr("type") == "dynamic"
        val periods = mpd.children("Period")
        if (periods.size > 1) throw UnsupportedStreamException("Multi-period DASH cannot be saved yet. Try preview playback.")
        val period = periods.firstOrNull() ?: throw IOException("DASH manifest has no Period: $manifestUrl")
        val periodStart = parseDuration(period.attr("start")) ?: 0.0
        val periodDuration = parseDuration(period.attr("duration"))
            ?: parseDuration(mpd.attr("mediaPresentationDuration"))?.minus(periodStart)
        if (!periodStart.isFinite() || periodStart < 0 || (periodDuration != null && (!periodDuration.isFinite() || periodDuration <= 0)))
            throw UnsupportedStreamException("Invalid DASH period interval.")
        if (period.children("AdaptationSet").sumOf { it.children("Representation").size } > 256)
            throw UnsupportedStreamException("DASH manifest has too many representations.")

        val mpdBase = baseOf(mpd, manifestUrl)
        val periodBase = baseOf(period, mpdBase)
        val video = ArrayList<DashTrack>()
        val audio = ArrayList<DashTrack>()
        val subtitles = ArrayList<String>()

        for (set in period.children("AdaptationSet")) {
            val setBase = baseOf(set, periodBase)
            val setProtected = (mpd.children("ContentProtection") + period.children("ContentProtection") + set.children("ContentProtection")).isNotEmpty()
            for (rep in set.children("Representation")) {
                val mime = rep.attr("mimeType") ?: set.attr("mimeType") ?: ""
                val contentType = set.attr("contentType") ?: mime.substringBefore('/')
                val mimeLower = mime.lowercase()
                val base = baseOf(rep, setBase)
                val isText = contentType == "text" || "vtt" in mimeLower || "ttml" in mimeLower
                if (isText) {
                    if ((base.startsWith("http://") || base.startsWith("https://")) &&
                        base.substringBefore('?') != manifestUrl.substringBefore('?')
                    ) {
                        subtitles.add(base)
                    }
                    continue
                }
                val isVideo = contentType == "video"
                if (!isVideo && contentType != "audio") continue

                val id = rep.attr("id") ?: ""
                val bandwidth = rep.attr("bandwidth")?.toLongOrNull() ?: 0L
                val levels = listOf(period, set, rep)
                for (mode in listOf("SegmentTemplate", "SegmentList", "SegmentBase")) {
                    val offset = levels.mapNotNull { it.children(mode).firstOrNull()?.attr("presentationTimeOffset") }.lastOrNull()
                    if (offset != null && offset.toLongOrNull() != 0L)
                        throw UnsupportedStreamException("DASH presentation offsets are not supported for saving yet. Try preview playback.")
                }
                val template = templateOf(levels)
                val list = rep.children("SegmentList").firstOrNull() ?: set.children("SegmentList").firstOrNull() ?: period.children("SegmentList").firstOrNull()

                val init: MediaSegment?
                val segments: List<MediaSegment>
                when {
                    template?.media != null -> {
                        init = template.initialization?.let { MediaSegment(HlsParser.resolve(base, fill(it, id, bandwidth, 0, 0))) }
                        segments = fromTemplate(template, id, bandwidth, base, periodDuration, budget)
                    }

                    list != null -> {
                        init = list.children("Initialization").firstOrNull()?.let { ranged(base, it.attr("sourceURL"), it.attr("range")) }
                        segments = list.children("SegmentURL").map { budget.take(ranged(base, it.attr("media"), it.attr("mediaRange"))) }
                    }

                    // SegmentBase or nothing at all: the BaseURL is one complete file.
                    else -> {
                        if (base == manifestUrl) throw UnsupportedStreamException("DASH representation has no media URL or segment plan.")
                        init = null
                        segments = listOf(budget.take(MediaSegment(base)))
                    }
                }
                if (segments.isEmpty()) continue

                val track = DashTrack(
                    id = id,
                    isVideo = isVideo,
                    mimeType = mime,
                    codecs = rep.attr("codecs") ?: set.attr("codecs"),
                    bandwidth = bandwidth,
                    width = (rep.attr("width") ?: set.attr("width"))?.toIntOrNull(),
                    height = (rep.attr("height") ?: set.attr("height"))?.toIntOrNull(),
                    isProtected = setProtected || rep.children("ContentProtection").isNotEmpty(),
                    init = init,
                    segments = segments,
                )
                if (isVideo) video.add(track) else audio.add(track)
            }
        }
        return DashManifest(video, audio, isLive, subtitles)
    }

    private fun fromTemplate(t: Template, id: String, bandwidth: Long, base: String, periodSeconds: Double?, budget: Budget): List<MediaSegment> {
        val media = t.media ?: return emptyList()
        val out = ArrayList<MediaSegment>()
        var number = t.startNumber

        val timeline = t.timeline
        if (timeline != null) {
            var time = 0L
            for ((entryIndex, entry) in timeline.withIndex()) {
                if (entry.start != null) time = entry.start
                if (time < 0 || t.startNumber < 0) throw UnsupportedStreamException("Invalid DASH segment timing.")
                if (entry.duration <= 0 || entry.repeat < -1 || entry.repeat >= MAX_SEGMENTS) throw IOException("Invalid DASH timeline duration or repeat count")
                val count = if (entry.repeat >= 0) entry.repeat + 1 else {
                    // r = -1 means "repeat until the end of the period".
                    val end = timeline.getOrNull(entryIndex + 1)?.start ?: ((periodSeconds ?: throw IOException("Unbounded DASH timeline")) * t.timescale).toLong()
                    if (end <= time) throw IOException("Invalid DASH timeline boundary")
                    ceil((end - time).coerceAtLeast(0).toDouble() / entry.duration).toInt().coerceAtLeast(1)
                }
                repeat(count) {
                    if (out.size >= MAX_SEGMENTS) throw IOException("DASH manifest lists too many segments")
                    out.add(budget.take(MediaSegment(HlsParser.resolve(base, fill(media, id, bandwidth, number, time)))))
                    number = Math.addExact(number, 1)
                    time = Math.addExact(time, entry.duration)
                }
            }
            return out
        }

        val duration = t.duration ?: throw UnsupportedStreamException("DASH template has no timeline or segment duration.")
        val seconds = periodSeconds ?: throw IOException("DASH manifest doesn't say how long the video is")
        val count = ceil(seconds * t.timescale / duration).toInt()
        if (count <= 0 || count > MAX_SEGMENTS) throw IOException("DASH manifest lists too many segments")
        for (index in 0 until count) {
            out.add(budget.take(MediaSegment(HlsParser.resolve(base, fill(media, id, bandwidth, Math.addExact(number, index.toLong()), Math.multiplyExact(index.toLong(), duration))))))
        }
        return out
    }

    /** SegmentTemplate attributes inherit downwards: Period < AdaptationSet < Representation. */
    private fun templateOf(levels: List<Element>): Template? {
        val templates = levels.mapNotNull { it.children("SegmentTemplate").firstOrNull() }
        if (templates.isEmpty()) return null
        // Effective offsets for every addressing mode are checked before expansion.
        fun pick(name: String): String? = templates.lastOrNull { it.attr(name) != null }?.attr(name)
        val timeline = templates.lastOrNull { it.children("SegmentTimeline").isNotEmpty() }
            ?.children("SegmentTimeline")?.first()?.children("S")
            ?.map {
                TimelineEntry(
                    start = it.attr("t")?.toLongOrNull(),
                    duration = it.attr("d")?.toLongOrNull() ?: 0L,
                    repeat = it.attr("r")?.toIntOrNull() ?: 0,
                )
            }
        return Template(
            media = pick("media"),
            initialization = pick("initialization"),
            startNumber = pick("startNumber")?.toLongOrNull() ?: 1L,
            timescale = pick("timescale")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L,
            duration = pick("duration")?.toLongOrNull()?.takeIf { it > 0 },
            timeline = timeline,
        )
    }

    internal fun fill(template: String, id: String, bandwidth: Long, number: Long, time: Long): String =
        placeholder.replace(template) { match ->
            val width = match.groupValues[2].toIntOrNull()
            fun padded(value: Long) = if (width != null) value.toString().padStart(width.coerceIn(0, 20), '0') else value.toString()
            when (match.groupValues[1]) {
                "RepresentationID" -> id
                "Number" -> padded(number)
                "Bandwidth" -> padded(bandwidth)
                else -> padded(time)
            }
        }.replace("$$", "$")

    private fun ranged(base: String, url: String?, range: String?): MediaSegment {
        val resolved = if (url.isNullOrEmpty()) base else HlsParser.resolve(base, url)
        val start = range?.substringBefore('-')?.trim()?.toLongOrNull()
        val end = range?.substringAfter('-', "")?.trim()?.toLongOrNull()
        if (range != null && (start == null || end == null || start < 0 || end < start || end == Long.MAX_VALUE)) throw IOException("Invalid DASH byte range")
        return if (start != null && end != null && end >= start) MediaSegment(resolved, start, end - start + 1)
        else MediaSegment(resolved)
    }

    private fun baseOf(element: Element, parentBase: String): String {
        val own = element.children("BaseURL").firstOrNull()?.textContent?.trim()
        return if (own.isNullOrEmpty()) parentBase else HlsParser.resolve(parentBase, own)
    }

    internal fun parseDuration(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val match = isoDuration.matchEntire(text.trim()) ?: return null
        val (days, hours, minutes, seconds) = match.destructured
        return (days.toDoubleOrNull() ?: 0.0) * 86_400 +
            (hours.toDoubleOrNull() ?: 0.0) * 3_600 +
            (minutes.toDoubleOrNull() ?: 0.0) * 60 +
            (seconds.toDoubleOrNull() ?: 0.0)
    }

    private fun Element.localTag(): String = tagName.substringAfter(':')

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.localTag() == name) out.add(node)
        }
        return out
    }
}
