package com.andrip.browser.download

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class UnsupportedStreamException(message: String) : IOException(message)
class MediaFinalizationException(message: String, cause: Throwable) : IOException(message, cause)
class HttpFailure(val code: Int, val retryAfterMs: Long? = null) : IOException(when (code) {
    401, 403 -> "Access denied (HTTP $code). Return to the source page, sign in if needed, play the video and detect a fresh link. The link may have expired or downloads may be restricted."
    404, 410 -> "This media is no longer available (HTTP $code). Reopen the source page to find a fresh link."
    429 -> "The server is busy (HTTP 429). Wait before trying again."
    else -> "The media server returned HTTP $code."
})

data class RequestHeaders(
    val referer: String?,
    val userAgent: String?,
    val sourceUrl: String? = null,
    val captured: Map<String, String> = emptyMap(),
) {
    companion object {
        fun capture(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
            it.lowercase() in setOf("origin", "accept", "accept-language", "authorization") || it.startsWith("x-", true)
        }.filterValues { !it.contains('\r') && !it.contains('\n') }
    }
}

interface BrowserCookies {
    fun get(url: String): String?
    fun set(url: String, cookie: String)
}

/** Applied on every network hop, including redirects: credentials never follow a different origin. */
class ContextInterceptor(private val cookies: BrowserCookies) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val context = request.tag(RequestHeaders::class.java)
        val builder = request.newBuilder()
        if (context != null) {
            val source = context.sourceUrl?.toHttpUrlOrNull()
            val sameOrigin = source != null && sameOrigin(source, request.url)
            context.captured.forEach { (name, value) ->
                builder.removeHeader(name)
                val publicHeader = name.lowercase() in setOf("accept", "accept-language", "origin")
                if (sameOrigin || (publicHeader && !(source?.isHttps == true && !request.url.isHttps))) {
                    runCatching { builder.header(name, value) }
                }
            }
            builder.removeHeader("Referer")
            context.referer?.toHttpUrlOrNull()?.let {
                if (!it.isHttps || request.url.isHttps) {
                    // Match strict-origin-when-cross-origin; never forward page paths or queries to another origin.
                    val safe = if (sameOrigin(it, request.url)) it.newBuilder().username("").password("").fragment(null).build()
                        else it.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null).build()
                    builder.header("Referer", safe.toString())
                }
            }
            context.userAgent?.let { runCatching { builder.header("User-Agent", it) } }
        }
        // CookieManager understands domain/path/Secure attributes; do not turn cookie pairs into a CookieJar.
        builder.removeHeader("Cookie")
        cookies.get(request.url.toString())?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        val response = chain.proceed(builder.build())
        response.headers.values("Set-Cookie").forEach { cookies.set(request.url.toString(), it) }
        return response
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
}

object HttpPolicy {
    fun request(url: String, headers: RequestHeaders, range: String? = null, validator: String? = null): Request {
        val parsed = url.toHttpUrlOrNull() ?: throw UnsupportedStreamException("Only HTTP and HTTPS media can be saved.")
        return Request.Builder().url(parsed)
            .tag(RequestHeaders::class.java, if (headers.sourceUrl == null) headers.copy(sourceUrl = url) else headers)
            .header("Accept-Encoding", "identity")
            .apply {
                range?.let { header("Range", it) }
                validator?.let { header("If-Range", it) }
            }.build()
    }

    fun check(response: Response) {
        if (!response.isSuccessful) throw HttpFailure(response.code, retryAfter(response.header("Retry-After")))
        val mime = response.body?.contentType()?.toString().orEmpty().lowercase()
        if (mime.startsWith("text/html") || mime.startsWith("application/json")) {
            throw UnsupportedStreamException("The server returned a sign-in page or an error instead of media. Reopen the source page.")
        }
    }

    fun retryable(error: IOException): Boolean = when (error) {
        is StorageFullException -> false
        is UnsupportedStreamException -> false
        is MediaFinalizationException -> false
        is HttpFailure -> error.code in setOf(408, 429, 500, 502, 503, 504)
        else -> true
    }

    fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
        if (value == null) return null
        return value.toLongOrNull()?.coerceIn(0, 300)?.times(1000)
            ?: runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 300_000) }.getOrNull()
    }

    fun message(error: Throwable): String = when (error) {
        is UnknownHostException -> "The media host could not be found. Check your connection, VPN or Private DNS, then retry."
        is ConnectException -> "The media host refused the connection. Check the site and your network, then retry."
        else -> com.andrip.browser.log.Redaction.clean(error.message ?: "The download could not finish. Try again.")
    }

    data class ContentRange(val start: Long, val end: Long, val total: Long?)
    fun contentRange(value: String?): ContentRange? {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull()
        return ContentRange(start, end, total).takeIf { end >= start && (total == null || end < total) }
    }
}
