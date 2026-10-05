package com.andrip.browser.download

import java.io.IOException
import java.net.URI

enum class HlsProtection { NONE, AES_128, DRM }

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

    val label: String
        get() = when {
            height != null -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "default"
        }
}

/** One HTTP fetch: a whole URL, or a byte range of it. Shared by HLS and DASH. */
data class MediaSegment(val url: String, val rangeStart: Long? = null, val rangeLength: Long? = null)

sealed interface HlsPlaylist

data class HlsMaster(val variants: List<HlsVariant>, val protection: HlsProtection) : HlsPlaylist {
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
) : HlsPlaylist

/** Minimal M3U8 parser: just what is needed to list qualities and fetch segments. */
object HlsParser {
    private val attribute = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) {
            throw IOException("Not an HLS playlist (missing #EXTM3U): $baseUrl")
        }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl)
        else parseMedia(lines, baseUrl)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsMaster {
        val audioPlaylists = HashMap<String, String>() // GROUP-ID -> playlist URL
        var protection = HlsProtection.NONE
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA:")) {
                val a = attributes(line)
                val group = a["GROUP-ID"]
                val uri = a["URI"]
                if (a["TYPE"] == "AUDIO" && !uri.isNullOrEmpty() && group != null) {
                    // Prefer the rendition marked DEFAULT, otherwise the first one listed.
                    if (group !in audioPlaylists || a["DEFAULT"] == "YES") audioPlaylists[group] = resolve(baseUrl, uri)
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
                        url = resolve(baseUrl, line),
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
        return HlsMaster(variants, protection)
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

        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") ->
                    duration += line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-KEY:") ->
                    protection = maxOf(protection, protectionOf(attributes(line)))

                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line)
                    val uri = a["URI"]
                    if (uri != null && init == null) {
                        val range = parseRange(a["BYTERANGE"], 0L)
                        init = MediaSegment(resolve(baseUrl, uri), range?.second, range?.first)
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val range = parseRange(line.substringAfter(':'), nextOffset)
                    if (range != null) {
                        pendingLength = range.first
                        pendingStart = range.second
                    }
                }

                line.startsWith("#EXT-X-ENDLIST") -> ended = true

                line.startsWith("#") -> Unit

                else -> {
                    val length = pendingLength
                    if (length != null) {
                        segments.add(MediaSegment(resolve(baseUrl, line), pendingStart, length))
                        nextOffset = pendingStart + length
                    } else {
                        segments.add(MediaSegment(resolve(baseUrl, line)))
                        nextOffset = 0L
                    }
                    pendingLength = null
                }
            }
        }
        return HlsMedia(segments, init, protection, isLive = !ended, durationSeconds = duration)
    }

    /** "length[@offset]" -> (length, offset). */
    private fun parseRange(spec: String?, defaultOffset: Long): Pair<Long, Long>? {
        if (spec.isNullOrBlank()) return null
        val length = spec.substringBefore('@').trim().toLongOrNull() ?: return null
        val offset = if (spec.contains('@')) spec.substringAfter('@').trim().toLongOrNull() ?: defaultOffset else defaultOffset
        return length to offset
    }

    private fun protectionOf(a: Map<String, String>): HlsProtection {
        val method = a["METHOD"]?.uppercase() ?: return HlsProtection.NONE
        val keyFormat = a["KEYFORMAT"]?.lowercase().orEmpty()
        val uri = a["URI"]?.lowercase().orEmpty()
        return when {
            method == "NONE" -> HlsProtection.NONE
            method.startsWith("SAMPLE-AES") -> HlsProtection.DRM
            keyFormat.isNotEmpty() && keyFormat != "identity" -> HlsProtection.DRM
            uri.startsWith("skd://") -> HlsProtection.DRM
            method == "AES-128" -> HlsProtection.AES_128
            else -> HlsProtection.DRM
        }
    }

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
}
