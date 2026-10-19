package com.andrip.browser

import com.andrip.browser.detect.MediaDetector
import com.andrip.browser.download.*
import com.andrip.browser.log.Redaction
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProtocolSafetyTest {
    @Test fun drmPathsAndFragmentsFromVimeoLog() {
        assertTrue(MediaDetector.isProtectedUrl("https://cdn.example/v2/playlist/drm/cenc/foo/playlist.m3u8"))
        assertNull(MediaDetector.classify("https://cdn.example/random-id.mp4?init_range=0-804&range=0-804"))
        assertFalse(MediaDetector.isProtectedUrl("https://player.vimeo.com/progressive_redirect/file.mp4?signature=abc"))
    }

    @Test(expected = UnsupportedStreamException::class) fun rejectsMultiPeriodInsteadOfSavingOnlyFirst() {
        DashParser.parse("<MPD><Period/><Period/></MPD>", "https://example.com/a.mpd")
    }

    @Test(expected = IOException::class) fun rejectsXmlEntitiesEvenOnParsersWithoutSecurityFeature() {
        DashParser.parse("<!DOCTYPE MPD [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><MPD>&secret;</MPD>", "https://e.com/a.mpd")
    }

    @Test fun negativeRepeatEndsAtNextTimelineStart() {
        val xml = """<MPD mediaPresentationDuration="PT20S"><Period><AdaptationSet mimeType="video/mp4">
            <Representation id="v"><SegmentTemplate timescale="1" media="${'$'}Time${'$'}.m4s">
            <SegmentTimeline><S t="0" d="2" r="-1"/><S t="6" d="7" r="1"/></SegmentTimeline>
            </SegmentTemplate></Representation></AdaptationSet></Period></MPD>"""
        assertEquals(listOf("0.m4s", "2.m4s", "4.m4s", "6.m4s", "13.m4s"),
            DashParser.parse(xml, "https://e.com/a.mpd").video.single().segments.map { it.url.substringAfterLast('/') })
    }

    @Test fun hlsDiscontinuityKeepsBothSegments() {
        val media = HlsParser.parse("#EXTM3U\n#EXTINF:3,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:3,\nb.ts\n#EXT-X-ENDLIST", "https://e.com/a.m3u8") as HlsMedia
        assertEquals(listOf("https://e.com/a.ts", "https://e.com/b.ts"), media.segments.map { it.url })
        assertTrue(media.discontinuity)
    }

    @Test(expected = IOException::class) fun implicitByteRangeCannotReferToAnotherResource() {
        HlsParser.parse("#EXTM3U\n#EXT-X-BYTERANGE:10@0\na.mp4\n#EXT-X-BYTERANGE:10\nb.mp4\n#EXT-X-ENDLIST", "https://e.com/a.m3u8")
    }

    @Test fun rangesAndRetryPolicy() {
        assertEquals(HttpPolicy.ContentRange(2, 5, 6), HttpPolicy.contentRange("bytes 2-5/6"))
        assertNull(HttpPolicy.contentRange("bytes 5-2/6"))
        assertNull(HttpPolicy.contentRange("bytes 2-6/6"))
        assertFalse(HttpPolicy.retryable(HttpFailure(403)))
        assertTrue(HttpPolicy.retryable(HttpFailure(429)))
        assertEquals(2000L, HttpPolicy.retryAfter("2"))
    }

    @Test(expected = IOException::class) fun malformedRangeDoesNotFallBackToWholeFile() {
        HlsParser.parse("#EXTM3U\n#EXT-X-BYTERANGE:not-a-number\nfile.mp4\n#EXT-X-ENDLIST", "https://e.com/a.m3u8")
    }

    @Test fun signedPathsAndQueriesAreRedacted() {
        val clean = Redaction.clean("Failed https://cdn.example/exp=123~hmac=secret/video.mp4?token=secret")
        assertEquals("Failed https://cdn.example/[redacted]", clean)
        assertFalse(clean.contains("secret"))
    }
}
