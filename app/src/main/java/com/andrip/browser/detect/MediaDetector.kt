package com.andrip.browser.detect

import com.andrip.browser.util.FileNames

enum class MediaKind(val label: String) {
    HLS("HLS stream"),
    DASH("DASH stream"),
    FILE("Video file"),
}

data class DetectedMedia(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String?,
    val pageTitle: String?,
    val referer: String?,
    val userAgent: String?,
    /** Where it was spotted: "network", "page", "download". Only used for the log. */
    val source: String,
)

/** Decides whether a URL (plus optional MIME type) is a video worth offering. */
object MediaDetector {
    private val fileExtensions = setOf("mp4", "webm", "m4v", "mov", "mkv")
    private val hlsMimes = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
    )
    private val fileMimes = setOf(
        "video/mp4", "video/webm", "video/quicktime", "video/x-matroska", "video/x-m4v",
    )

    // Pieces of a segmented stream (init.mp4, seg-12.mp4, chunk_3.m4v ...) are not whole videos.
    private val segmentName = Regex("^(init|seg|segment|chunk|frag|fragment)([-_.]|\\d)")

    fun classify(url: String, mimeType: String? = null): MediaKind? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extension = FileNames.extensionOf(url)
        val kind = when {
            mime in hlsMimes || extension == "m3u8" -> MediaKind.HLS
            mime == "application/dash+xml" || extension == "mpd" -> MediaKind.DASH
            mime in fileMimes || extension in fileExtensions -> MediaKind.FILE
            else -> return null
        }
        if (kind == MediaKind.FILE && segmentName.containsMatchIn(FileNames.fileNameOf(url).lowercase())) {
            return null
        }
        return kind
    }
}
