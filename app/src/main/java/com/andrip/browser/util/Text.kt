package com.andrip.browser.util

import java.net.URLEncoder

/** Turns whatever was typed in the address bar into something loadable. */
object UrlInput {
    const val HOME = "https://duckduckgo.com/"
    private val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")

    fun toUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return HOME
        if (hasScheme.matches(text)) return text
        val looksLikeHost = !text.contains(' ') && (text.contains('.') || text.startsWith("localhost"))
        return if (looksLikeHost) "https://$text"
        else "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
    }
}

object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")
    private val videoExtensions = setOf("mp4", "webm", "m4v", "mov", "mkv", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "ts")

    fun sanitize(title: String?): String {
        var clean = (title ?: "")
            .replace(illegal, " ")
            .replace(spaces, " ")
            .trim()
        while (clean.contains("..")) clean = clean.replace("..", ".")
        clean = clean.trim().trim('.').take(80).trim().trim('.')
        return clean.ifEmpty { "video" }
    }

    /** Lower-case extension of the last path segment, ignoring query and fragment. */
    fun extensionOf(url: String): String? {
        val name = fileNameOf(url)
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotEmpty() && it.length <= 5 }
    }

    fun fileNameOf(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/')

    fun videoExtension(url: String): String =
        extensionOf(url)?.takeIf { it in videoExtensions } ?: "mp4"

    fun extensionForMime(mime: String?): String? = when (mime) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        "video/mp2t" -> "ts"
        else -> null
    }

    fun mimeFor(extension: String): String = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }
}
