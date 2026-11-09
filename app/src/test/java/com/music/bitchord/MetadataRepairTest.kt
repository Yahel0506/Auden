package com.music.bitchord

import com.music.bitchord.download.MetadataRepair
import com.music.bitchord.download.MetadataRepair.Gap
import com.music.bitchord.download.MetadataRepair.Outcome
import com.music.bitchord.download.MetadataRepair.Report
import com.music.bitchord.data.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRepairTest {

    private fun tags(
        title: String? = "T",
        artist: String? = "A",
        album: String? = "L",
        picture: ByteArray? = byteArrayOf(1),
    ) = MetadataRepair.Tags(
        title = title, artist = artist, album = album, albumArtist = null,
        track = null, year = null, picture = picture,
    )

    @Test
    fun `a complete file has no gaps`() {
        assertTrue(MetadataRepair.gapsOf(tags()).isEmpty())
    }

    @Test
    fun `blank and absent text counts as missing`() {
        assertEquals(setOf(Gap.TITLE), MetadataRepair.gapsOf(tags(title = "  ")))
        assertEquals(setOf(Gap.ARTIST, Gap.ALBUM), MetadataRepair.gapsOf(tags(artist = null, album = "")))
    }

    @Test
    fun `no cover or an empty one is a cover gap`() {
        assertEquals(setOf(Gap.COVER), MetadataRepair.gapsOf(tags(picture = null)))
        assertEquals(setOf(Gap.COVER), MetadataRepair.gapsOf(tags(picture = ByteArray(0))))
    }

    @Test
    fun `lyrics are not a gap`() {
        // The check has no lyrics field: a file without them is complete.
        assertTrue(MetadataRepair.gapsOf(tags()).none { it.name == "LYRICS" })
    }

    @Test
    fun `report counts each outcome and skips files`() {
        val report = listOf(Outcome.REPAIRED, Outcome.COMPLETE, Outcome.UNAVAILABLE, Outcome.NOT_A_FILE)
            .fold(Report()) { acc, outcome -> acc + outcome }
        assertEquals(Report(repaired = 1, complete = 1, unavailable = 1), report)
        assertEquals(3, report.checked)
    }

    @Test
    fun `release notes fall back to English for an unknown language`() {
        assertEquals(listOf("1.12.4/es.md", "1.12.4/en.md"), ReleaseNotes.candidates("1.12.4", "es"))
        assertEquals(listOf("1.12.4/fr.md", "1.12.4/en.md"), ReleaseNotes.candidates("1.12.4", "fr"))
        assertEquals(listOf("1.12.4/en.md"), ReleaseNotes.candidates("1.12.4", ""))
    }
}
