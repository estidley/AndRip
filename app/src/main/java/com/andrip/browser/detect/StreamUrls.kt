package com.andrip.browser.detect

/**
 * URLs that must always reach the page's own player and the download engine.
 * The ad blocker uses this so a manifest or segment is never replaced, even when
 * its host is also on the block list.
 */
object StreamUrls {
    private val extensions = setOf(
        "m3u8", "m3u", "mpd", "m4s", "mp4", "m4v", "m4a", "m4f", "webm", "mkv", "mov",
        "ts", "aac", "mp3", "ogg", "opus", "wav", "flac", "cmfv", "cmfa", "cmft", "fmp4",
        "3gp", "ismv", "isma", "vtt",
    )

    fun mustPassThrough(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("m3u8") || lower.contains("mpegurl") || lower.contains(".mpd")) return true
        val path = lower.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('.', "")
        if (ext in extensions) return true
        return path.contains("/hls/") || path.contains("/dash/") || path.contains("/cmfv/") || path.contains("/cmfa/")
    }
}
