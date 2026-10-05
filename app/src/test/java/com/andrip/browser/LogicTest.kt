package com.andrip.browser

import com.andrip.browser.detect.MediaDetector
import com.andrip.browser.detect.MediaKind
import com.andrip.browser.download.DashParser
import com.andrip.browser.download.HlsMaster
import com.andrip.browser.download.HlsMedia
import com.andrip.browser.download.HlsParser
import com.andrip.browser.download.HlsProtection
import com.andrip.browser.download.MediaSegment
import com.andrip.browser.log.LogFile
import com.andrip.browser.util.FileNames
import com.andrip.browser.util.UrlInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LogicTest {

    // ---- detection ----

    @Test fun classifiesByExtensionAndMime() {
        assertEquals(MediaKind.HLS, MediaDetector.classify("https://cdn.example.com/v/master.m3u8?token=abc"))
        assertEquals(MediaKind.DASH, MediaDetector.classify("https://cdn.example.com/v/manifest.mpd"))
        assertEquals(MediaKind.FILE, MediaDetector.classify("http://example.com/clip.MP4#t=10"))
        assertEquals(MediaKind.HLS, MediaDetector.classify("https://example.com/play", "application/x-mpegURL; charset=utf-8"))
        assertEquals(MediaKind.FILE, MediaDetector.classify("https://example.com/get?id=5", "video/webm"))
    }

    @Test fun ignoresNonMediaAndSegments() {
        assertNull(MediaDetector.classify("https://example.com/index.html"))
        assertNull(MediaDetector.classify("https://example.com"))
        assertNull(MediaDetector.classify("blob:https://example.com/1234"))
        assertNull(MediaDetector.classify("https://cdn.example.com/v/seg-12.mp4"))
        assertNull(MediaDetector.classify("https://cdn.example.com/v/init.mp4"))
        assertNull(MediaDetector.classify("https://cdn.example.com/v/chunk_3.m4v"))
        assertEquals(MediaKind.FILE, MediaDetector.classify("https://cdn.example.com/v/segway-review.mp4"))
    }

    // ---- HLS ----

    @Test fun parsesMasterAndPicksQuality() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            https://other.example.com/hi/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            /abs/mid.m3u8?x=1
        """.trimIndent()
        val master = HlsParser.parse(text, "https://cdn.example.com/v/master.m3u8?t=1") as HlsMaster
        assertEquals(3, master.variants.size)
        assertEquals("https://cdn.example.com/v/low/index.m3u8", master.variants[0].url)
        assertEquals("https://cdn.example.com/abs/mid.m3u8?x=1", master.variants[2].url)
        assertEquals("avc1.4d401e,mp4a.40.2", master.variants[0].codecs)
        assertEquals(1080, master.pick()!!.height)
        assertEquals("https://other.example.com/hi/index.m3u8", master.pick()!!.url)
        assertEquals(720, master.pick(maxHeight = 720)!!.height)
        assertEquals(360, master.pick(maxHeight = 100)!!.height)
        assertEquals("1080p", master.pick()!!.label)
        assertFalse(master.pick()!!.separateAudio)
        assertEquals(HlsProtection.NONE, master.protection)
    }

    @Test fun flagsSeparateAudio() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Spanish",URI="audio/es.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="muxed",NAME="English"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            hi.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="muxed"
            low.m3u8
        """.trimIndent()
        val master = HlsParser.parse(text, "https://e.com/m.m3u8") as HlsMaster
        assertTrue(master.variants[0].separateAudio)
        assertEquals("https://e.com/audio/en.m3u8", master.variants[0].audioUrl)
        assertFalse(master.variants[1].separateAudio)
        assertNull(master.variants[1].audioUrl)
    }

    @Test fun parsesMediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            a.ts
            #EXTINF:4.5,
            sub/b.ts?sig=9
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parse(text, "https://e.com/v/index.m3u8") as HlsMedia
        assertEquals(listOf("https://e.com/v/a.ts", "https://e.com/v/sub/b.ts?sig=9"), media.segments.map { it.url })
        assertNull(media.initSegment)
        assertFalse(media.isLive)
        assertEquals(10.5, media.durationSeconds, 0.001)
        assertEquals(HlsProtection.NONE, media.protection)
    }

    @Test fun parsesFragmentedMp4WithByteRanges() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="700@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@700
            main.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            main.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parse(text, "https://e.com/v/index.m3u8") as HlsMedia
        assertEquals(0L, media.initSegment!!.rangeStart)
        assertEquals(700L, media.initSegment!!.rangeLength)
        assertEquals(700L, media.segments[0].rangeStart)
        assertEquals(1000L, media.segments[0].rangeLength)
        assertEquals(1700L, media.segments[1].rangeStart)
        assertEquals(500L, media.segments[1].rangeLength)
    }

    @Test fun detectsLiveAndProtection() {
        fun media(key: String) = HlsParser.parse("#EXTM3U\n$key\n#EXTINF:4,\na.ts\n", "https://e.com/i.m3u8") as HlsMedia
        assertTrue(media("").isLive)
        assertEquals(HlsProtection.NONE, media("#EXT-X-KEY:METHOD=NONE").protection)
        assertEquals(HlsProtection.AES_128, media("#EXT-X-KEY:METHOD=AES-128,URI=\"https://e.com/k\"").protection)
        assertEquals(HlsProtection.DRM, media("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://abc\",KEYFORMAT=\"com.apple.streamingkeydelivery\"").protection)
        assertEquals(HlsProtection.DRM, media("#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"").protection)
    }

    @Test(expected = IOException::class) fun rejectsNonPlaylist() {
        HlsParser.parse("<html>403</html>", "https://e.com/i.m3u8")
    }

    // ---- DASH ----

    @Test fun dashNumberTemplate() {
        val xml = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1M10S">
              <BaseURL>media/</BaseURL>
              <Period>
                <AdaptationSet mimeType="video/mp4" segmentAlignment="true">
                  <SegmentTemplate timescale="1000" duration="4000" startNumber="1"
                      initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number%05d${'$'}.m4s"/>
                  <Representation id="v720" bandwidth="2000000" width="1280" height="720" codecs="avc1.64001f"/>
                  <Representation id="v1080" bandwidth="5000000" width="1920" height="1080" codecs="avc1.640028"/>
                  <Representation id="av1" bandwidth="4000000" width="3840" height="2160" codecs="av01.0.12M.08"/>
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
                  <SegmentTemplate timescale="48000" duration="192000" initialization="a/init.mp4" media="a/${'$'}Number${'$'}.m4s"/>
                  <Representation id="a1" bandwidth="128000" codecs="mp4a.40.2"/>
                </AdaptationSet>
                <AdaptationSet contentType="text" mimeType="text/vtt">
                  <Representation id="t1" bandwidth="100"><BaseURL>subs.vtt</BaseURL></Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashParser.parse(xml, "https://cdn.example.com/v/manifest.mpd?t=1")
        assertFalse(manifest.isLive)
        assertEquals(3, manifest.video.size)
        assertEquals(1, manifest.audio.size)
        val video = manifest.bestVideo()!!
        assertEquals("v1080", video.id) // AV1 can't go into an MP4 on most phones, so H.264 wins
        assertEquals("https://cdn.example.com/v/media/v1080/init.mp4", video.init!!.url)
        assertEquals(18, video.segments.size) // ceil(70s / 4s)
        assertEquals("https://cdn.example.com/v/media/v1080/seg-00001.m4s", video.segments.first().url)
        assertEquals("https://cdn.example.com/v/media/v1080/seg-00018.m4s", video.segments.last().url)
        assertEquals("v720", manifest.bestVideo(maxHeight = 720)!!.id)
        val audio = manifest.bestAudio(video)!!
        assertEquals("https://cdn.example.com/v/media/a/init.mp4", audio.init!!.url)
        assertEquals("https://cdn.example.com/v/media/a/1.m4s", audio.segments.first().url)
        assertEquals(18, audio.segments.size)
        assertFalse(video.isProtected)
    }

    @Test fun dashTimelineTemplate() {
        val xml = """
            <MPD type="static" mediaPresentationDuration="PT20S">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="900000" height="480">
                    <SegmentTemplate timescale="1000" startNumber="7" initialization="i.mp4" media="t${'$'}Time${'$'}_n${'$'}Number${'$'}_b${'$'}Bandwidth${'$'}.m4s">
                      <SegmentTimeline>
                        <S t="5000" d="4000" r="2"/>
                        <S d="2000"/>
                        <S d="3000" r="-1"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val track = DashParser.parse(xml, "https://e.com/d/m.mpd").video.single()
        assertEquals(
            listOf(
                "https://e.com/d/t5000_n7_b900000.m4s",
                "https://e.com/d/t9000_n8_b900000.m4s",
                "https://e.com/d/t13000_n9_b900000.m4s",
                "https://e.com/d/t17000_n10_b900000.m4s",
                "https://e.com/d/t19000_n11_b900000.m4s", // r=-1: one 3s piece reaches the 20s end
            ),
            track.segments.map { it.url },
        )
    }

    @Test fun dashSingleFileListAndProtection() {
        val xml = """
            <MPD type="dynamic">
              <Period duration="PT1H2M3.5S">
                <AdaptationSet mimeType="video/webm">
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                  <Representation id="v" bandwidth="1" height="360" codecs="vp9">
                    <BaseURL>https://other.example.com/whole.webm</BaseURL>
                    <SegmentBase indexRange="100-200"/>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a" bandwidth="2">
                    <SegmentList>
                      <Initialization sourceURL="a.mp4" range="0-99"/>
                      <SegmentURL media="a.mp4" mediaRange="100-599"/>
                      <SegmentURL media="a.mp4" mediaRange="600-899"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashParser.parse(xml, "https://e.com/d/m.mpd")
        assertTrue(manifest.isLive)
        val video = manifest.video.single()
        assertTrue(video.isProtected)
        assertTrue(video.isWebm)
        assertNull(video.init)
        assertEquals(listOf(MediaSegment("https://other.example.com/whole.webm")), video.segments)
        val audio = manifest.audio.single()
        assertFalse(audio.isProtected)
        assertEquals(MediaSegment("https://e.com/d/a.mp4", 0, 100), audio.init)
        assertEquals(MediaSegment("https://e.com/d/a.mp4", 600, 300), audio.segments[1])
        assertEquals(3723.5, DashParser.parseDuration("PT1H2M3.5S")!!, 0.001)
    }

    @Test(expected = IOException::class) fun dashRejectsNonManifest() {
        DashParser.parse("<html><body>403</body></html>", "https://e.com/m.mpd")
    }

    // ---- text helpers ----

    @Test fun addressBarInput() {
        assertEquals("https://example.com", UrlInput.toUrl(" example.com "))
        assertEquals("http://example.com/a", UrlInput.toUrl("http://example.com/a"))
        assertEquals("https://duckduckgo.com/?q=big+buck+bunny", UrlInput.toUrl("big buck bunny"))
        assertEquals("https://duckduckgo.com/?q=kotlin", UrlInput.toUrl("kotlin"))
        assertEquals(UrlInput.HOME, UrlInput.toUrl("  "))
    }

    @Test fun fileNames() {
        assertEquals("My Video Part 1", FileNames.sanitize("My: Video / Part 1?"))
        assertEquals("video", FileNames.sanitize("  "))
        assertEquals("video", FileNames.sanitize(null))
        assertEquals(80, FileNames.sanitize("x".repeat(200)).length)
        assertEquals("webm", FileNames.videoExtension("https://e.com/a.webm?x=1"))
        assertEquals("mp4", FileNames.videoExtension("https://e.com/get?id=1"))
        assertEquals("video/mp2t", FileNames.mimeFor("ts"))
    }

    // ---- log file ----

    @Test fun logKeepsFullStackTraceWithCauses() {
        val dir = Files.createTempDirectory("andrip-log").toFile()
        val log = LogFile(dir)
        val error = IllegalStateException("outer boom", IOException("inner cause"))
        log.write("I", "Test", "hello")
        log.write("E", "Test", "it broke", error)
        val text = log.readAll()
        assertTrue(text.contains("I/Test"))
        assertTrue(text.contains("hello"))
        assertTrue(text.contains("java.lang.IllegalStateException: outer boom"))
        assertTrue(text.contains("Caused by: java.io.IOException: inner cause"))
        assertTrue(text.contains("at com.andrip.browser.LogicTest"))

        val exported = log.export(File(dir, "out/andrip-log.txt"), "HEADER LINE")
        assertTrue(exported.readText().startsWith("HEADER LINE\n\n"))
        assertTrue(exported.readText().contains("inner cause"))

        log.clear()
        assertEquals("", log.readAll())
        dir.deleteRecursively()
    }

    @Test fun logRotatesInsteadOfGrowingForever() {
        val dir = Files.createTempDirectory("andrip-log").toFile()
        val log = LogFile(dir, maxBytes = 2_000)
        repeat(200) { log.write("D", "Test", "line number $it " + "x".repeat(40)) }
        assertTrue(log.current.length() < 4_000)
        assertTrue(File(dir, "andrip.log.1").exists())
        assertTrue(log.readAll().contains("line number 199"))
        assertTrue(log.tail(500).length <= 510)
        dir.deleteRecursively()
    }
}
