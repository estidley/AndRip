package com.andrip.browser

import com.andrip.browser.download.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit

class TransferTest {
    private val headers = RequestHeaders(null, "AndRip test")
    private fun partial(target: java.io.File, url: String) {
        target.writeText("abc")
        Properties().apply {
            setProperty("url", url); setProperty("validator", "\"one\"")
            java.io.File(target.path + ".resume").outputStream().use { store(it, null) }
        }
    }

    @Test fun resumesOnlyValidatedMatchingRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"one\"").setBody("def"))
            val target = Files.createTempFile("resume", ".mp4").toFile()
            partial(target, server.url("/movie").toString())
            MediaTransfer(OkHttpClient()).file(server.url("/movie").toString(), headers, target) { _, _ -> }
            assertEquals("abcdef", target.readText())
            val request = server.takeRequest()
            assertEquals("bytes=3-", request.getHeader("Range"))
            assertEquals("\"one\"", request.getHeader("If-Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }

    @Test fun rangeIgnoredRestartsWithoutAppending() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("ETag", "\"two\"").setBody("NEW FILE"))
            val target = Files.createTempFile("restart", ".mp4").toFile()
            partial(target, server.url("/movie").toString())
            MediaTransfer(OkHttpClient()).file(server.url("/movie").toString(), headers, target) { _, _ -> }
            assertEquals("NEW FILE", target.readText())
        }
    }

    @Test fun invalidRangeNeverCorruptsExistingBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/6").setBody("cdef"))
            server.enqueue(MockResponse().setBody("abcdef"))
            val target = Files.createTempFile("invalid", ".mp4").toFile()
            partial(target, server.url("/movie").toString())
            MediaTransfer(OkHttpClient()).file(server.url("/movie").toString(), headers, target) { _, _ -> }
            assertEquals("abcdef", target.readText())
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun ambiguous416Restarts() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */3"))
            server.enqueue(MockResponse().setBody("fresh"))
            val target = Files.createTempFile("range416", ".mp4").toFile()
            partial(target, server.url("/movie").toString())
            MediaTransfer(OkHttpClient()).file(server.url("/movie").toString(), headers, target) { _, _ -> }
            assertEquals("fresh", target.readText())
        }
    }

    @Test fun unvalidatedPartialIsNotResumed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete"))
            val target = Files.createTempFile("no-validator", ".mp4").toFile().apply { writeText("old") }
            MediaTransfer(OkHttpClient()).file(server.url("/").toString(), headers, target) { _, _ -> }
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals("complete", target.readText())
        }
    }

    @Test fun signed403DoesNotRetryOrExposeTheUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val target = Files.createTempFile("denied", ".mp4").toFile()
            try {
                MediaTransfer(OkHttpClient()).file(server.url("/movie?signature=secret").toString(), headers, target) { _, _ -> }
                fail("Expected denial")
            } catch (e: HttpFailure) {
                assertEquals(403, e.code)
                assertTrue(e.message!!.contains("fresh link"))
                assertFalse(e.message!!.contains("secret"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun retriesTransientStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("media"))
            val target = Files.createTempFile("retry", ".mp4").toFile()
            MediaTransfer(OkHttpClient()).file(server.url("/").toString(), headers, target) { _, _ -> }
            assertEquals("media", target.readText())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun segmentRequiresExactRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("whole file"))
            try {
                MediaTransfer(OkHttpClient()).segment(MediaSegment(server.url("/").toString(), 3, 3), headers, Files.createTempFile("seg", ".tmp").toFile())
                fail("Must not concatenate an ignored range")
            } catch (_: UnsupportedStreamException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsKeepPublicContextButScopeCredentialsAndCookies() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            cdn.enqueue(MockResponse().setBody("media"))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/video")).setHeader("Set-Cookie", "redirect=fresh; Path=/"))
            val seen = mutableListOf<Pair<String,String>>()
            val cookies = object : BrowserCookies {
                override fun get(url: String) = if (url.startsWith(origin.url("/").toString())) "session=origin" else "session=cdn"
                override fun set(url: String, cookie: String) { seen += url to cookie }
            }
            val client = OkHttpClient.Builder().addNetworkInterceptor(ContextInterceptor(cookies)).build()
            val url = origin.url("/signed?signature=unchanged").toString()
            val context = RequestHeaders(origin.url("/page").toString(), "Browser UA", url, mapOf("Authorization" to "Bearer secret", "X-Token" to "secret", "Origin" to origin.url("/").toString()))
            MediaTransfer(client).file(url, context, Files.createTempFile("redirect", ".mp4").toFile()) { _, _ -> }
            val first = origin.takeRequest()
            assertEquals("Bearer secret", first.getHeader("Authorization"))
            assertEquals("session=origin", first.getHeader("Cookie"))
            assertEquals("/signed?signature=unchanged", first.path)
            val second = cdn.takeRequest()
            assertNull(second.getHeader("Authorization"))
            assertNull(second.getHeader("X-Token"))
            assertEquals("Browser UA", second.getHeader("User-Agent"))
            assertEquals(origin.url("/").toString(), second.getHeader("Referer"))
            assertEquals("session=cdn", second.getHeader("Cookie"))
            assertEquals(1, seen.size)
        } }
    }

    @Test fun htmlIsNotSavedAsVideo() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Log in</html>"))
            try {
                MediaTransfer(OkHttpClient()).file(server.url("/").toString(), headers, Files.createTempFile("html", ".mp4").toFile()) { _, _ -> }
                fail("Expected sign-in rejection")
            } catch (_: UnsupportedStreamException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun disconnectRetriesFromValidatedPartialBytes() = runBlocking {
        MockWebServer().use { server ->
            // Disabling OkHttp connection retries below also disables fallback between localhost
            // IPv4/IPv6 routes. Pin both ends so this fixture tests body recovery on every OS.
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            var requests = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests++
                    if (requests == 1) return MockResponse().setBody("abcdefghij").setHeader("ETag", "\"one\"")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    val start = request.getHeader("Range")!!.substringAfter('=').substringBefore('-').toInt()
                    return MockResponse().setResponseCode(206).setHeader("ETag", "\"one\"")
                        .setHeader("Content-Range", "bytes $start-9/10").setBody("abcdefghij".substring(start))
                }
            }
            val target = Files.createTempFile("disconnect", ".mp4").toFile()
            MediaTransfer(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
                .file(server.url("/movie").newBuilder().host("127.0.0.1").build().toString(), headers, target) { _, _ -> }
            assertEquals("abcdefghij", target.readText())
            assertEquals(2, requests)
        }
    }

    @Test fun cancellationClosesBlockedNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = launch(kotlinx.coroutines.Dispatchers.IO) {
                MediaTransfer(OkHttpClient()).file(server.url("/").toString(), headers,
                    Files.createTempFile("cancel", ".mp4").toFile()) { _, _ -> }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
