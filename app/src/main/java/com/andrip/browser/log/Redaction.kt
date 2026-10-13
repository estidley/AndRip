package com.andrip.browser.log

import java.net.URI

/** Signed URLs can put credentials in paths as well as queries. Logs retain only the host. */
object Redaction {
    private val urls = Regex("https?://[^\\s<>\"\\)]+", RegexOption.IGNORE_CASE)
    private val headers = Regex("(?im)(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=][^\\r\\n]*")
    private val bearer = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val fields = Regex("(?i)([\"']?(?:access_token|refresh_token|id_token|token|password|passwd|secret|api[_-]?key|session(?:id)?)['\"]?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)")
    fun clean(text: String): String {
        val safe = fields.replace(bearer.replace(headers.replace(text) { "${it.groupValues[1]}: [redacted]" }, "$1 [redacted]")) { "${it.groupValues[1]}[redacted]" }
        return urls.replace(safe) {
        runCatching { URI(it.value).let { u -> "${u.scheme}://${u.host}/[redacted]" } }.getOrDefault("[redacted URL]")
        }
    }
}
