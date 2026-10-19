package com.andrip.browser.browser

import com.andrip.browser.detect.StreamUrls

/**
 * Host-list ad and tracker blocking. Matching is offline and fail-open:
 * a main-frame navigation, a stream manifest/segment, or any uncertainty is left alone.
 */
object AdBlock {
    @Volatile
    var domains: Set<String> = emptySet()
        private set

    fun load(text: String) {
        domains = parseHosts(text)
    }

    fun parseHosts(text: String): Set<String> = text.lineSequence().map { line ->
        line.substringBefore('#').trim().lowercase()
    }.filter { host ->
        host.isNotEmpty() && host.contains('.') && !host.contains('/') &&
            !host.contains(' ') && !host.contains(':') && !host.contains('*')
    }.toHashSet()

    fun shouldBlock(
        url: String,
        mainFrame: Boolean,
        enabled: Boolean,
        domains: Set<String> = this.domains,
    ): Boolean {
        if (!enabled || mainFrame) return false
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        if (StreamUrls.mustPassThrough(url)) return false
        val host = hostOf(url) ?: return false
        return listed(host, domains)
    }

    fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = if (authority.startsWith("[")) return null else authority.substringBefore(':')
        return host.lowercase().trim('.').takeIf { it.isNotEmpty() && it.contains('.') }
    }

    /** True when [host] or one of its registrable parents is in [domains]. Single labels are never matched. */
    fun listed(host: String, domains: Set<String>): Boolean {
        var current = host.lowercase().trim('.')
        while (current.contains('.')) {
            if (current in domains) return true
            current = current.substringAfter('.')
        }
        return false
    }
}
