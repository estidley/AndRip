package com.andrip.browser.download

import java.io.IOException
import java.net.URI

enum class HlsProtection { NONE, AES_128, SAMPLE_AES, DRM }

data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    /** Set when the audio lives in its own playlist and has to be merged in afterwards. */
    val audioUrl: String?,
) {
    val separateAudio: Boolean get() = audioUrl != null

    /**
     * A variant that already lists an audio codec is muxed. Downloading the audio
     * rendition as well makes a second file the TS muxer cannot merge, and throws away
     * a playable self-contained stream. Empty codecs plus an audio group still means
     * the audio is separate.
     */
    fun fetchSeparateAudio(): Boolean {
        if (audioUrl == null) return false
        val codecs = codecs?.lowercase().orEmpty()
        if (codecs.isEmpty()) return true
        val audioCodecs = listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "mp3", "vorbis", "alac", "dts")
        val hasAudio = codecs.split(',').any { codec -> audioCodecs.any { codec.trim().startsWith(it) } }
        return !hasAudio
    }

    val label: String
        get() = when {
            height != null -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "default"
        }
}

data class HlsRendition(val url: String, val language: String?, val name: String?)

/** One HTTP fetch: a whole URL, or a byte range of it. Shared by HLS and DASH. */
data class MediaSegment(
    val url: String,
    val rangeStart: Long? = null,
    val rangeLength: Long? = null,
    /** HLS AES-128 key URL. Null for clear media. */
    val keyUrl: String? = null,
    /** 32 hex characters, without a 0x prefix. */
    val iv: String? = null,
)

sealed interface HlsPlaylist

data class HlsMaster(
    val variants: List<HlsVariant>,
    val protection: HlsProtection,
    val subtitles: List<HlsRendition> = emptyList(),
) : HlsPlaylist {
    /** Best quality, or the best one at or under [maxHeight]. */
    fun pick(maxHeight: Int? = null): HlsVariant? {
        val sorted = variants.sortedWith(
            compareByDescending<HlsVariant> { it.height ?: 0 }.thenByDescending { it.bandwidth },
        )
        if (maxHeight == null) return sorted.firstOrNull()
        return sorted.firstOrNull { (it.height ?: 0) <= maxHeight } ?: sorted.lastOrNull()
    }
}

data class HlsMedia(
    val segments: List<MediaSegment>,
    val initSegment: MediaSegment?,
    val protection: HlsProtection,
    val isLive: Boolean,
    val durationSeconds: Double,
    val discontinuity: Boolean = false,
    val skippedGaps: Int = 0,
) : HlsPlaylist

/** Minimal M3U8 parser: just what is needed to list qualities and fetch segments. */
object HlsParser {
    private val attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun parse(text: String, baseUrl: String): HlsPlaylist {
        if (text.length > 4 * 1024 * 1024) throw IOException("Playlist is too large to process safely")
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size > 400_000) throw IOException("Playlist has too many lines")
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) {
            throw IOException("Not an HLS playlist (missing #EXTM3U): $baseUrl")
        }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl)
        else parseMedia(lines, baseUrl)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsMaster {
        val audioPlaylists = HashMap<String, String>() // GROUP-ID -> playlist URL
        val subtitles = ArrayList<HlsRendition>()
        var protection = HlsProtection.NONE
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val a = attributes(line)
                val group = a["GROUP-ID"]
                val uri = a["URI"]
                if (a["TYPE"] == "AUDIO" && !uri.isNullOrEmpty() && group != null) {
                    // Prefer the rendition marked DEFAULT, otherwise the first one listed.
                    if (group !in audioPlaylists || a["DEFAULT"] == "YES") {
                        audioPlaylists[group] = requireHttp(resolve(baseUrl, uri))
                    }
                } else if (a["TYPE"] == "SUBTITLES" && !uri.isNullOrEmpty()) {
                    val resolved = resolve(baseUrl, uri)
                    if (isHttp(resolved)) subtitles.add(HlsRendition(resolved, a["LANGUAGE"], a["NAME"]))
                }
            } else if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                protection = maxOf(protection, protectionOf(attributes(line)))
            }
        }

        val variants = ArrayList<HlsVariant>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = attributes(line)
            } else if (!line.startsWith("#")) {
                val a = pending ?: continue
                val resolution = a["RESOLUTION"]?.lowercase()?.split('x')
                val audioGroup = a["AUDIO"]
                variants.add(
                    HlsVariant(
                        url = requireHttp(resolve(baseUrl, line)),
                        bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = resolution?.getOrNull(0)?.toIntOrNull(),
                        height = resolution?.getOrNull(1)?.toIntOrNull(),
                        codecs = a["CODECS"],
                        audioUrl = if (audioGroup != null) audioPlaylists[audioGroup] else null,
                    ),
                )
                pending = null
            }
        }
        return HlsMaster(variants, protection, subtitles)
    }

    private fun parseMedia(lines: List<String>, baseUrl: String): HlsMedia {
        val segments = ArrayList<MediaSegment>()
        var init: MediaSegment? = null
        var protection = HlsProtection.NONE
        var ended = false
        var duration = 0.0
        var pendingStart = 0L
        var pendingLength: Long? = null
        var nextOffset = 0L
        var previousUrl: String? = null
        var implicitOffset = false
        var sequence = 0L
        var aes = false
        var keyUrl: String? = null
        var explicitIv: String? = null
        var gapNext = false
        var discontinuity = false
        var skippedGaps = 0

        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") ->
                    duration += line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    if (segments.isNotEmpty() || skippedGaps > 0) throw IOException("HLS media sequence must come before segments")
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: throw IOException("Invalid HLS media sequence")
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line)
                    protection = maxOf(protection, protectionOf(a))
                    when (a["METHOD"]?.uppercase()) {
                        "NONE", null -> {
                            aes = false
                            keyUrl = null
                            explicitIv = null
                        }
                        "AES-128" -> {
                            aes = true
                            val uri = a["URI"] ?: throw IOException("AES-128 key has no URI")
                            keyUrl = requireHttp(resolve(baseUrl, uri))
                            explicitIv = a["IV"]?.let(::parseIv)
                        }
                        else -> {
                            aes = false
                            keyUrl = null
                            explicitIv = null
                        }
                    }
                }

                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true

                line == "#EXT-X-GAP" -> gapNext = true

                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line)
                    val uri = a["URI"]
                    if (uri == null) throw IOException("HLS initialization segment has no URI")
                    run {
                        val range = parseRange(a["BYTERANGE"], 0L)
                        val next = MediaSegment(requireHttp(resolve(baseUrl, uri)), range?.second, range?.first)
                        if (init != null && init != next) throw UnsupportedStreamException("Changing HLS initialization segments are not supported for saving.")
                        init = next
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    implicitOffset = !line.substringAfter(':').contains('@')
                    val range = parseRange(line.substringAfter(':'), nextOffset)
                    if (range != null) {
                        pendingLength = range.first
                        pendingStart = range.second
                    }
                }

                line.startsWith("#EXT-X-ENDLIST") -> ended = true

                line.startsWith("#") -> Unit

                else -> {
                    if (segments.size >= 100_000) throw IOException("HLS playlist lists too many segments")
                    val resolved = requireHttp(resolve(baseUrl, line))
                    if (implicitOffset && (previousUrl != resolved || nextOffset == 0L)) throw IOException("HLS byte range has no valid preceding range")
                    val length = pendingLength
                    if (length != null) nextOffset = pendingStart + length else nextOffset = 0L
                    pendingLength = null
                    implicitOffset = false
                    previousUrl = resolved
                    if (gapNext) {
                        gapNext = false
                        skippedGaps++
                        sequence++
                        continue
                    }
                    val iv = if (aes) explicitIv ?: HlsAes.sequenceIv(sequence) else null
                    if (aes && keyUrl == null) throw IOException("AES-128 segment has no key")
                    segments.add(
                        MediaSegment(
                            resolved,
                            if (length != null) pendingStart else null,
                            length,
                            if (aes) keyUrl else null,
                            iv,
                        ),
                    )
                    sequence++
                }
            }
        }
        return HlsMedia(segments, init, protection, isLive = !ended, durationSeconds = duration, discontinuity = discontinuity, skippedGaps = skippedGaps)
    }

    /** "length[@offset]" -> (length, offset). */
    private fun parseRange(spec: String?, defaultOffset: Long): Pair<Long, Long>? {
        if (spec == null) return null
        val length = spec.substringBefore('@').trim().toLongOrNull() ?: throw IOException("Invalid HLS byte range length")
        val offset = if (spec.contains('@')) spec.substringAfter('@').trim().toLongOrNull() ?: throw IOException("Invalid HLS byte range offset") else defaultOffset
        if (length <= 0 || offset < 0 || offset > Long.MAX_VALUE - length) throw IOException("Invalid HLS byte range")
        return length to offset
    }

    private fun protectionOf(a: Map<String, String>): HlsProtection {
        val method = a["METHOD"]?.uppercase() ?: return HlsProtection.NONE
        val keyFormat = a["KEYFORMAT"]?.lowercase().orEmpty()
        val uri = a["URI"]?.lowercase().orEmpty()
        val commercial = uri.startsWith("skd://") || (keyFormat.isNotEmpty() && keyFormat != "identity")
        return when {
            method == "NONE" -> HlsProtection.NONE
            method.startsWith("SAMPLE-AES") && commercial -> HlsProtection.DRM
            method.startsWith("SAMPLE-AES") -> HlsProtection.SAMPLE_AES
            commercial -> HlsProtection.DRM
            method == "AES-128" -> HlsProtection.AES_128
            else -> HlsProtection.DRM
        }
    }

    private fun parseIv(raw: String): String = HlsAes.ivBytes(raw).joinToString("") { "%02x".format(it) }

    internal fun attributes(line: String): Map<String, String> =
        attribute.findAll(line.substringAfter(':', "")).associate {
            it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")
        }

    internal fun resolve(base: String, reference: String): String = try {
        URI(base).resolve(reference).toString()
    } catch (_: Exception) {
        if (reference.contains("://")) reference
        else base.substringBefore('?').substringBeforeLast('/') + "/" + reference
    }

    internal fun requireHttp(url: String): String {
        if (!isHttp(url)) throw IOException("Refusing a non-HTTP media URL")
        return url
    }

    private fun isHttp(url: String): Boolean {
        val scheme = try {
            URI(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        }
        return scheme == "http" || scheme == "https"
    }
}
