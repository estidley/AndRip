package com.andrip.browser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Byte-accurate transfers, independent of Android so the HTTP contract is tested on the JVM. */
class MediaTransfer(private val client: OkHttpClient) {
    private suspend fun <T> response(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { block(it) } } finally { cancellation.cancel() }
    }

    suspend fun <T> retry(block: suspend () -> T): T {
        repeat(3) { attempt ->
            coroutineContext.ensureActive()
            try { return block() } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (attempt == 2 || !HttpPolicy.retryable(e)) throw e
                delay((e as? HttpFailure)?.retryAfterMs ?: ((500L shl attempt) + Random.nextLong(250)))
            }
        }
        error("Unreachable")
    }

    suspend fun file(url: String, headers: RequestHeaders, target: File, progress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val sidecar = File(target.path + ".resume")
        var contentType: String? = null
        retry {
            val metadata = Properties().apply { if (sidecar.exists()) runCatching { sidecar.inputStream().use { load(it) } } }
            val validator = metadata.getProperty("validator").takeIf { metadata.getProperty("url") == url }
            val existing = if (validator != null && target.exists()) target.length() else 0L
            val range = if (existing > 0) "bytes=$existing-" else null
            response(HttpPolicy.request(url, headers, range, validator.takeIf { range != null })) { response ->
                if (response.code == 416 && range != null) {
                    // A stale/ambiguous 416 is never proof of completion.
                    sidecar.delete()
                    throw IOException("The server cannot resume this file; restarting it safely.")
                }
                HttpPolicy.check(response)
                val body = response.body ?: throw IOException("The server returned no media.")
                contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                val returnedRange = if (response.code == 206) HttpPolicy.contentRange(response.header("Content-Range")) else null
                val append = response.code == 206 && existing > 0
                if (response.code == 206) {
                    if (returnedRange == null || returnedRange.start != existing || returnedRange.total == null || returnedRange.end != returnedRange.total - 1) {
                        sidecar.delete()
                        throw IOException("The server returned an invalid byte range; restarting safely.")
                    }
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified")
                    if (append && responseValidator != null && responseValidator != validator) {
                        sidecar.delete()
                        throw IOException("This media changed on the server; restarting safely.")
                    }
                } else if (response.code != 200) throw UnsupportedStreamException("Unexpected media response (HTTP ${response.code}).")
                val offset = if (append) existing else 0L
                val length = body.contentLength()
                val expected = returnedRange?.let { it.end - it.start + 1 } ?: length
                val total = returnedRange?.total ?: if (length >= 0) offset + length else -1L
                StorageSpace.requireSpace(target.parentFile!!, expected.coerceAtLeast(0))
                FileOutputStream(target, append).use { out ->
                    // Opening/truncating the destination precedes replacing the validator.
                    val next = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified")
                    if (next == null) sidecar.delete() else Properties().apply {
                        setProperty("url", url); setProperty("validator", next)
                        sidecar.outputStream().use { store(it, null) }
                    }
                    var copied = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (copied % (4 * 1024 * 1024) < buffer.size) StorageSpace.requireSpace(target.parentFile!!)
                            out.write(buffer, 0, count)
                            copied += count
                            val done = offset + copied
                            progress(if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1,
                                if (total > 0) "${done / 1024} KB of ${total / 1024} KB" else "${done / 1024} KB")
                        }
                    }
                    if (expected >= 0 && copied != expected) throw IOException("The connection ended before the media was complete.")
                    if (offset + copied == 0L) throw UnsupportedStreamException("The server returned an empty media file.")
                }
            }
        }
        contentType
    }

    suspend fun segment(segment: MediaSegment, headers: RequestHeaders, target: File) = withContext(Dispatchers.IO) {
        retry {
            val start = segment.rangeStart
            val length = segment.rangeLength
            val range = if (start != null && length != null) "bytes=$start-${start + length - 1}" else null
            response(HttpPolicy.request(segment.url, headers, range)) { response ->
                HttpPolicy.check(response)
                if (range != null) {
                    val actual = HttpPolicy.contentRange(response.header("Content-Range"))
                    if (response.code != 206 || actual == null || actual.start != start || actual.end - actual.start + 1 != length) {
                        throw UnsupportedStreamException("The server did not honor a required segment byte range.")
                    }
                } else if (response.code != 200) throw UnsupportedStreamException("The server returned an incomplete media segment.")
                val body = response.body ?: throw IOException("Empty media segment.")
                StorageSpace.requireSpace(target.parentFile!!, (length ?: body.contentLength()).coerceAtLeast(0))
                var copied = 0L
                FileOutputStream(target).use { out -> body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (copied % (4 * 1024 * 1024) < buffer.size) StorageSpace.requireSpace(target.parentFile!!)
                        out.write(buffer, 0, count); copied += count
                    }
                } }
                val expected = length ?: body.contentLength()
                if (copied == 0L || (expected >= 0 && copied != expected)) throw IOException("Incomplete media segment; retrying.")
            }
        }
    }

    suspend fun text(url: String, headers: RequestHeaders): Pair<String, String> = withContext(Dispatchers.IO) {
        retry {
            response(HttpPolicy.request(url, headers)) { response ->
                HttpPolicy.check(response)
                val body = response.body ?: throw IOException("Empty manifest.")
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 4 * 1024 * 1024) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 4 * 1024 * 1024) throw UnsupportedStreamException("This manifest is too large to process safely.")
                bytes.toString(Charsets.UTF_8) to response.request.url.toString()
            }
        }
    }
}
