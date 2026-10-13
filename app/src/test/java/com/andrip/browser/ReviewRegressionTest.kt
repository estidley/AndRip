package com.andrip.browser

import com.andrip.browser.download.*
import com.andrip.browser.log.Redaction
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Regression cases from the adversarial review. All tokens and hosts are synthetic. */
class ReviewRegressionTest {
    @Test fun offsetsAreCheckedAcrossInheritedBaseAndListModes() {
        for (mode in listOf("SegmentBase", "SegmentList", "SegmentTemplate")) {
            val xml = """<MPD mediaPresentationDuration="PT5S"><Period>
                <$mode presentationTimeOffset="10"/>
                <AdaptationSet mimeType="video/mp4"><Representation id="v"><BaseURL>movie.mp4</BaseURL></Representation></AdaptationSet>
                </Period></MPD>"""
            assertThrows(UnsupportedStreamException::class.java) { DashParser.parse(xml, "https://example.invalid/a.mpd") }
        }
    }

    @Test fun childZeroOffsetOverridesParentOffset() {
        val xml = """<MPD mediaPresentationDuration="PT5S"><Period><SegmentTemplate presentationTimeOffset="10" duration="5" media="s-${'$'}Number${'$'}.m4s"/>
            <AdaptationSet mimeType="video/mp4"><Representation id="v"><SegmentTemplate presentationTimeOffset="0"/></Representation></AdaptationSet>
            </Period></MPD>"""
        assertEquals(1, DashParser.parse(xml, "https://example.invalid/a.mpd").video.single().segments.size)
    }

    @Test fun dashPeriodStartUsesRemainingInterval() {
        val xml = """<MPD type="static" mediaPresentationDuration="PT20S"><Period start="PT10S">
            <AdaptationSet mimeType="video/mp4"><SegmentTemplate duration="5" media="part-${'$'}Number${'$'}.m4s"/>
            <Representation id="v" bandwidth="100"/></AdaptationSet></Period></MPD>"""
        val actual = DashParser.parse(xml, "https://example.invalid/manifest.mpd").video.single().segments.size
        assertEquals(2, actual)
    }

    @Test fun dashAggregateBudgetRejectsExpansion() {
        val reps = (1..24).joinToString("") { """<Representation id="$it" bandwidth="$it"/>""" }
        val xml = """<MPD type="static" mediaPresentationDuration="PT5000S"><Period>
            <AdaptationSet mimeType="video/mp4"><SegmentTemplate duration="1" media="${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s"/>
            $reps</AdaptationSet></Period></MPD>"""
        assertThrows(UnsupportedStreamException::class.java) { DashParser.parse(xml, "https://example.invalid/manifest.mpd") }
    }

    @Test fun segmentListOffsetsAreRefused() {
        val xml = """<MPD type="static" mediaPresentationDuration="PT5S"><Period><AdaptationSet mimeType="video/mp4">
            <Representation id="v" bandwidth="100"><SegmentList timescale="1" presentationTimeOffset="10" duration="5">
            <Initialization sourceURL="init.mp4"/><SegmentURL media="one.m4s"/>
            </SegmentList></Representation></AdaptationSet></Period></MPD>"""
        assertThrows(UnsupportedStreamException::class.java) { DashParser.parse(xml, "https://example.invalid/manifest.mpd") }
    }

    @Test fun logRedactionRemovesNonUrlCredentials() {
        val cleaned = Redaction.clean("Authorization: Bearer audit-fake-secret\nCookie: session=audit-fake-cookie\nhttps://example.invalid/?token=hidden")
        assertFalse(cleaned.contains("audit-fake-secret"))
        assertFalse(cleaned.contains("audit-fake-cookie"))
        assertFalse(cleaned.contains("token=hidden"))
    }

    @Test fun redirectReducesSensitiveReferrerToOrigin() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { other ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/movie")))
            other.enqueue(MockResponse().setBody("bytes"))
            val cookies = object : BrowserCookies {
                override fun get(url: String): String? = null
                override fun set(url: String, cookie: String) {}
            }
            val client = OkHttpClient.Builder().addNetworkInterceptor(ContextInterceptor(cookies)).build()
            val url = origin.url("/movie").toString()
            val referer = origin.url("/private-page?token=audit-fake-secret").toString()
            val file = Files.createTempFile("audit-referrer", ".mp4").toFile()
            try {
                MediaTransfer(client).file(url, RequestHeaders(referer, "audit", url), file) { _, _ -> }
                val received = other.takeRequest().getHeader("Referer")
                assertEquals(origin.url("/").toString(), received)
                assertEquals(referer, origin.takeRequest().getHeader("Referer"))
            } finally { file.delete() }
        } }
    }
}
