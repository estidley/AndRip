package com.andrip.browser.browser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Empty successful body for a blocked ad or tracker. Never used for media manifests or segments. */
internal fun blockedAdResponse(url: String): WebResourceResponse {
    val path = url.substringBefore('#').substringBefore('?').lowercase()
    val mime = when {
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        else -> "text/plain"
    }
    val body = if (mime == "application/javascript") "/* blocked */" else ""
    return WebResourceResponse(
        mime,
        "utf-8",
        200,
        "OK",
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store",
        ),
        ByteArrayInputStream(body.toByteArray()),
    )
}
