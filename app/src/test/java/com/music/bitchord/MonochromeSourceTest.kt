package com.music.bitchord

import com.music.bitchord.data.sources.MonochromeSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeSourceTest {

    private fun flacHead(rate: Int, bits: Int, channels: Int = 2): ByteArray {
        val head = ByteArray(42)
        "fLaC".toByteArray().copyInto(head)
        val info = 8
        head[info + 10] = (rate shr 12).toByte()
        head[info + 11] = ((rate shr 4) and 0xff).toByte()
        head[info + 12] = (((rate and 0xf) shl 4) or ((channels - 1) shl 1) or (((bits - 1) shr 4) and 1)).toByte()
        head[info + 13] = (((bits - 1) and 0xf) shl 4).toByte()
        return head
    }

    private fun mp4Head(): ByteArray {
        val head = ByteArray(42)
        byteArrayOf(0, 0, 0, 0x1c).copyInto(head)
        "ftypisom".toByteArray().copyInto(head, 4)
        return head
    }

    @Test
    fun `a FLAC's depth and rate are read off its streaminfo`() {
        val format = MonochromeSource.formatOf(flacHead(96_000, 24), 40_000_000L, 200)
        assertEquals("flac", format?.codec)
        assertEquals(24, format?.bitDepth)
        assertEquals(96_000, format?.sampleRateHz)
        assertEquals(true, format?.isLossless)
    }

    @Test
    fun `an MP4 is judged by its bitrate and marked inferred`() {
        // 14.87 MB over 369 s is the 320 kbps AAC the server hands out for most tracks
        val format = MonochromeSource.formatOf(mp4Head(), 14_868_756L, 369)
        assertEquals("aac", format?.codec)
        assertEquals(322, format?.kbps)
        assertTrue(format?.kbpsInferred == true)
        assertTrue(SourceResolver.beatsYouTubeAac(format!!))
    }

    @Test
    fun `an MP4 too big to be AAC is not called lossy`() {
        val format = MonochromeSource.formatOf(mp4Head(), 30_000_000L, 200)
        assertNull(format?.codec)
        assertNull(format?.kbps)
    }

    @Test
    fun `something that is not audio is refused`() {
        assertNull(MonochromeSource.formatOf("{\"error\":\"nope\"}".toByteArray().copyOf(42), 100L, 10))
    }

    @Test
    fun `the High rung keeps lossy audio and CD quality FLAC, not hi-res`() {
        assertTrue(SourceResolver.fitsHighRung(StreamFormat(codec = "aac", kbps = 320)))
        assertTrue(SourceResolver.fitsHighRung(StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)))
        assertTrue(SourceResolver.fitsHighRung(StreamFormat(codec = "flac")))
        assertFalse(SourceResolver.fitsHighRung(StreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 44_100)))
        assertFalse(SourceResolver.fitsHighRung(StreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 96_000)))
    }

    @Test
    fun `search rows read the streamer's answer`() {
        val source = MonochromeSource(SourceConfig(kind = SourceKind.MONOCHROME))
        val rows = source.rowsIn(
            """{"tracks":[
                {"trackId":"1","id":"1","title":"Get Lucky","artistNames":["Daft Punk","Pharrell Williams"],
                 "artwork":"https://x/a.jpg","explicit":false,"playable":true,"duration":369626},
                {"trackId":"2","title":"Gone","artistNames":["Nobody"],"playable":false,"duration":1000}
            ]}""",
        )
        assertEquals(1, rows.size)
        assertEquals("Daft Punk, Pharrell Williams", rows[0].artist)
        assertEquals(369, rows[0].durationSec)
    }

    @Test
    fun `the source is seeded only where the build carries the server`() {
        val with = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = false, monochromeAvailable = true)
        val monochrome = with.single { it.kind == SourceKind.MONOCHROME }
        assertTrue(monochrome.enabled)
        assertTrue(monochrome.isComplete)

        val without = SourceRegistry.sourcesForInit(with, forceJioSaavnOff = false, monochromeAvailable = false)
        assertNull(without.firstOrNull { it.kind == SourceKind.MONOCHROME })
        assertNotNull(without.firstOrNull { it.kind == SourceKind.YOUTUBE })
    }
}
