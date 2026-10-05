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
    private val videoExtensions = setOf("mp4", "webm", "m4v", "mov", "mkv")

    fun sanitize(title: String?): String {
        val clean = (title ?: "")
            .replace(illegal, " ")
            .replace(spaces, " ")
            .trim().trim('.')
            .take(80).trim()
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

    fun mimeFor(extension: String): String = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }
}
