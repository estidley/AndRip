package com.andrip.browser

import com.andrip.browser.browser.AdBlock
import com.andrip.browser.detect.MediaDetector
import com.andrip.browser.download.DashParser
import com.andrip.browser.download.DownloadEngine
import com.andrip.browser.download.HlsAes
import com.andrip.browser.download.HlsMaster
import com.andrip.browser.download.HlsMedia
import com.andrip.browser.download.HlsParser
import com.andrip.browser.download.HlsProtection
import com.andrip.browser.download.MediaProtection
import com.andrip.browser.download.RequestHeaders
import com.andrip.browser.util.FileNames
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class StreamRedTeamTest {
    private val headers = RequestHeaders("https://videos.example/watch", "AndRip test")

    @Test fun aes128CarriesSequenceIvAndSkipsGaps() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-KEY:METHOD=AES-128,URI="keys/a.key",IV=0x0000000000000000000000000000002a
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/b.key"
            #EXTINF:4,
            b.ts
            #EXT-X-GAP
            #EXTINF:4,
            c.ts
            #EXTINF:4,
            d.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parse(text, "https://cdn.example/v/index.m3u8") as HlsMedia
        assertEquals(HlsProtection.AES_128, media.protection)
        assertEquals(1, media.skippedGaps)
        assertEquals(listOf("a.ts", "b.ts", "d.ts"), media.segments.map { it.url.substringAfterLast('/') })
        assertEquals("https://cdn.example/v/keys/a.key", media.segments[0].keyUrl)
        assertEquals("0000000000000000000000000000002a", media.segments[0].iv)
        assertEquals("https://keys.example/b.key", media.segments[1].keyUrl)
        assertEquals(HlsAes.sequenceIv(6), media.segments[1].iv)
        assertEquals(HlsAes.sequenceIv(8), media.segments[2].iv)
        assertFalse(media.isLive)
    }

    @Test fun sampleAesWithoutACommercialKeyFormatIsExplicit() {
        val media = HlsParser.parse(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"https://e.com/k\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST",
            "https://e.com/i.m3u8",
        ) as HlsMedia
        assertEquals(HlsProtection.SAMPLE_AES, media.protection)
    }

    @Test fun rejectsNonHttpSegmentsAndKeepsChangingMapsUnsupported() {
        try {
            HlsParser.parse("#EXTM3U\n#EXTINF:1,\nfile:///etc/passwd\n#EXT-X-ENDLIST", "https://e.com/a.m3u8")
            throw AssertionError("file URL was accepted")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("non-HTTP"))
        }
        try {
            HlsParser.parse("#EXTM3U\n#EXTINF:1,\njavascript:alert(1)\n#EXT-X-ENDLIST", "https://e.com/a.m3u8")
            throw AssertionError("javascript URL was accepted")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("non-HTTP"))
        }
        try {
            HlsParser.parse(
                "#EXTM3U\n#EXT-X-MAP:URI=\"a.mp4\"\n#EXTINF:1,\na.m4s\n#EXT-X-MAP:URI=\"b.mp4\"\n#EXTINF:1,\nb.m4s\n#EXT-X-ENDLIST",
                "https://e.com/a.m3u8",
            )
            throw AssertionError("changing map was accepted")
        } catch (_: com.andrip.browser.download.UnsupportedStreamException) {
        }
    }

    @Test fun muxedVariantDoesNotFetchTheAudioGroup() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,LANGUAGE="en",URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="English",LANGUAGE="en",URI="subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
            hi.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            lo.m3u8
        """.trimIndent()
        val master = HlsParser.parse(text, "https://e.com/m.m3u8") as HlsMaster
        assertFalse(master.variants[0].fetchSeparateAudio())
        assertTrue(master.variants[1].fetchSeparateAudio())
        assertEquals("en", master.subtitles.single().language)
        assertEquals("https://e.com/subs/en.vtt", master.subtitles.single().url)
    }

    @Test fun dashCollectsATextTrackWithoutTreatingItAsVideo() {
        val xml = """
            <MPD type="static" mediaPresentationDuration="PT4S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000" codecs="avc1">
                    <BaseURL>video.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet contentType="text" mimeType="text/vtt">
                  <Representation id="t" bandwidth="1"><BaseURL>captions.vtt</BaseURL></Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val manifest = DashParser.parse(xml, "https://e.com/v/manifest.mpd")
        assertEquals(listOf("https://e.com/v/captions.vtt"), manifest.subtitles)
        assertEquals(1, manifest.video.size)
        assertTrue(manifest.audio.isEmpty())
    }

    @Test fun adAndPreviewUrlsAreNotOffered() {
        assertTrue(MediaDetector.looksLikeAd("https://cdn.example/preroll.m3u8"))
        assertTrue(MediaDetector.looksLikeAd("https://cdn.example/v/ads/master.m3u8"))
        assertTrue(MediaDetector.looksLikeAd("https://cdn.example/bumper/clip.mp4"))
        assertFalse(MediaDetector.looksLikeAd("https://cdn.example/adaptive/master.m3u8"))
        assertFalse(MediaDetector.looksLikeAd("https://cdn.example/segway-review.mp4"))
        val previous = AdBlock.domains
        try {
            AdBlock.load("doubleclick.net\n")
            assertTrue(MediaDetector.looksLikeAd("https://pubads.g.doubleclick.net/v/master.m3u8"))
        } finally {
            AdBlock.load(previous.joinToString("\n"))
        }
    }

    @Test fun filenamesCannotEscapeTheExportFolder() {
        assertEquals("video", FileNames.sanitize(".."))
        assertEquals("video", FileNames.sanitize("../"))
        assertEquals("etc passwd", FileNames.sanitize("../etc/passwd"))
        assertFalse(FileNames.sanitize("..\\..\\Windows\\system32").contains("\\"))
        assertFalse(FileNames.sanitize("..\\..\\Windows\\system32").contains("/"))
    }

    @Test fun decryptsAes128FromALocalPlaylist() = runBlocking {
        val key = ByteArray(16) { (it + 3).toByte() }
        val plain = "picture-and-sound".toByteArray()
        val iv = HlsAes.sequenceIv(0)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(HlsAes.ivBytes(iv)))
        val encrypted = cipher.doFinal(plain)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/index.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n",
                    )
                    "/key.bin" -> MockResponse().setBody(okio.Buffer().write(key))
                    "/a.ts" -> MockResponse().setBody(okio.Buffer().write(encrypted))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val dir = Files.createTempDirectory("aes").toFile()
            val output = engine().downloadHls(server.url("/index.m3u8").toString(), headers, dir) { _, _ -> }
            assertEquals("ts", output.extension)
            assertEquals("picture-and-sound", output.file.readText())
        }
    }

    @Test fun refreshesASignedSegmentUrlOnce() = runBlocking {
        MockWebServer().use { server ->
            var playlists = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/v.m3u8") -> {
                            playlists++
                            val token = if (playlists == 1) "old" else "new"
                            MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nseg.ts?token=$token\n#EXT-X-ENDLIST\n")
                        }
                        path.contains("token=old") -> MockResponse().setResponseCode(403)
                        path.startsWith("/seg.ts") -> MockResponse().setBody("fresh")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val dir = Files.createTempDirectory("refresh").toFile()
            val output = engine().downloadHls(server.url("/v.m3u8").toString(), headers, dir) { _, _ -> }
            assertEquals("fresh", output.file.readText())
            assertEquals(2, playlists)
        }
    }

    @Test fun liveWindowAndDiscontinuityConcatenate() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/live.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n")
                    "/disc.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST\n")
                    "/a.ts" -> MockResponse().setBody("A")
                    "/b.ts" -> MockResponse().setBody("B")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val liveDir = Files.createTempDirectory("live").toFile()
            val live = engine().downloadHls(server.url("/live.m3u8").toString(), headers, liveDir) { _, detail ->
                if (detail.startsWith("0 of")) assertTrue(detail.contains("live window"))
            }
            assertEquals("AB", live.file.readText())
            val discDir = Files.createTempDirectory("disc").toFile()
            val disc = engine().downloadHls(server.url("/disc.m3u8").toString(), headers, discDir) { _, _ -> }
            assertEquals("AB", disc.file.readText())
        }
    }

    @Test fun skipsSeparateAudioWhenTheVariantAlreadyHasSound() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/master.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",DEFAULT=YES,URI=\"audio.m3u8\"\n" +
                            "#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.4d401e,mp4a.40.2\",AUDIO=\"aud\"\nvideo.m3u8\n",
                    )
                    "/video.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nv.ts\n#EXT-X-ENDLIST\n")
                    "/audio.m3u8" -> MockResponse().setResponseCode(500)
                    "/v.ts" -> MockResponse().setBody("VIDEO")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val dir = Files.createTempDirectory("muxed").toFile()
            val output = engine().downloadHls(server.url("/master.m3u8").toString(), headers, dir) { _, _ -> }
            assertEquals("VIDEO", output.file.readText())
            assertNull(output.sidecar)
        }
    }

    @Test fun savesASubtitleSidecarAndRefusesProtectedPlaylists() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/master.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"sub\",NAME=\"English\",URI=\"en.vtt\"\n" +
                            "#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8\n",
                    )
                    "/video.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:1,\nv.ts\n#EXT-X-ENDLIST\n")
                    "/en.vtt" -> MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n")
                    "/v.ts" -> MockResponse().setBody("VIDEO")
                    "/drm.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://abc\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n",
                    )
                    "/sample.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"https://e.com/k\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val dir = Files.createTempDirectory("subs").toFile()
            val output = engine().downloadHls(server.url("/master.m3u8").toString(), headers, dir) { _, _ -> }
            assertEquals("VIDEO", output.file.readText())
            assertEquals("vtt", output.sidecar!!.extension)
            assertTrue(output.sidecar!!.readText().startsWith("WEBVTT"))
            val drm = runCatching { engine().downloadHls(server.url("/drm.m3u8").toString(), headers, dir) { _, _ -> } }
            assertEquals(MediaProtection.PROTECTED, drm.exceptionOrNull()!!.message)
            val sample = runCatching { engine().downloadHls(server.url("/sample.m3u8").toString(), headers, dir) { _, _ -> } }
            assertTrue(sample.exceptionOrNull()!!.message!!.contains("SAMPLE-AES"))
        }
    }

    @Test fun savesTheCurrentDashWindow() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/live.mpd" -> MockResponse().setHeader("Content-Type", "application/dash+xml").setBody(
                        """<MPD type="dynamic"><Period duration="PT4S"><AdaptationSet mimeType="video/mp4">
                            <Representation id="v" bandwidth="1000" codecs="avc1"><SegmentList>
                            <SegmentURL media="v.m4s"/></SegmentList></Representation>
                            </AdaptationSet></Period></MPD>""",
                    )
                    "/v.m4s" -> MockResponse().setBody("DASH")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val dir = Files.createTempDirectory("dash").toFile()
            val output = DownloadEngine(OkHttpClient(), inspect = {}, remux = glue()).downloadDash(
                server.url("/live.mpd").toString(), headers, dir,
            ) { _, detail ->
                if (detail.startsWith("0 of")) assertTrue(detail.contains("live window"))
            }
            assertEquals("mp4", output.extension)
            assertEquals("DASH", output.file.readText())
        }
    }

    @Test fun nestedMastersStop() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nnext.m3u8\n")
            }
            val dir = Files.createTempDirectory("nest").toFile()
            val error = runCatching { engine().downloadHls(server.url("/master.m3u8").toString(), headers, dir) { _, _ -> } }
            assertTrue(error.exceptionOrNull()!!.message!!.contains("nesting"))
        }
    }

    private fun engine() = DownloadEngine(OkHttpClient(), inspect = {}, remux = glue())

    private fun glue(): suspend (List<File>, File) -> File = { inputs, directory ->
        File(directory, "final.mp4").also { out ->
            out.outputStream().use { stream -> inputs.forEach { stream.write(it.readBytes()) } }
        }
    }
}
