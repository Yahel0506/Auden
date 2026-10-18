package com.music.bitchord

import com.music.bitchord.download.FlacTagger
import com.music.bitchord.download.Mp4Tagger
import com.music.bitchord.download.TagExtras
import com.music.bitchord.download.WebmTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagExtrasTest {
    private val extras = TagExtras(albumArtist = "The Band", track = 3, trackTotal = 12, disc = 1, year = 2020, comment = "https://music.youtube.com/watch?v=abc")

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    private fun mp4() = box("ftyp", "isom".toByteArray()) + box("moov", ByteArray(0))

    private fun flac(): ByteArray {
        val streamInfo = ByteArray(34)
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, streamInfo.size.toByte()) + streamInfo + byteArrayOf(0xFF.toByte())
    }

    private fun webm(): ByteArray {
        val header = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + byteArrayOf(0x84.toByte()) + ByteArray(4)
        val body = ByteArray(8)
        val segment = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67) + byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, body.size.toByte()) + body
        return header + segment
    }

    @Test
    fun flacGetsTheLibraryFields() {
        val tagged = FlacTagger.tag(flac(), "T", "A", "Al", null, null, "image/jpeg", extras = extras)
        listOf("ALBUMARTIST=The Band", "TRACKNUMBER=3", "TRACKTOTAL=12", "DISCNUMBER=1", "DATE=2020", "COMMENT=https://music.youtube.com/watch?v=abc")
            .forEach { assertTrue(it, tagged.has(it)) }
    }

    @Test
    fun mp4GetsTheLibraryAtoms() {
        val tagged = Mp4Tagger.tag(mp4(), "T", "A", "Al", null, null, false, extras = extras)
        listOf("aART", "trkn", "disk", "©day", "©cmt").forEach { assertTrue(it, tagged.has(it)) }
        assertTrue(tagged.has("The Band"))
    }

    @Test
    fun webmGetsTheLibraryTags() {
        val tagged = WebmTagger.tag(webm(), "T", "A", "Al", null, null, "image/jpeg", extras = extras)
        listOf("ALBUMARTIST", "TRACKNUMBER", "3/12", "DISCNUMBER", "DATE_RELEASED", "COMMENT").forEach { assertTrue(it, tagged.has(it)) }
    }

    @Test
    fun noExtrasWritesWhatItAlwaysDid() {
        val plain = FlacTagger.tag(flac(), "T", "A", "Al", null, null, "image/jpeg")
        assertFalse(plain.has("TRACKNUMBER"))
        assertEquals(plain.size, FlacTagger.tag(flac(), "T", "A", "Al", null, null, "image/jpeg", extras = TagExtras()).size)
    }

    @Test
    fun trackTextShowsTheTotalWhenKnown() {
        assertEquals("3/12", extras.trackText())
        assertEquals("3", extras.copy(trackTotal = null).trackText())
        assertEquals(null, TagExtras().trackText())
    }
}
