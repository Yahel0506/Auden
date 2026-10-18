package com.music.bitchord

import com.music.bitchord.download.DownloadLayout
import com.music.bitchord.download.DownloadLayout.Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLayoutTest {
    private val album = Info(title = "Song", artist = "Artist", album = "Album", albumArtist = "Band", track = 3, disc = 1, year = 2020)

    @Test
    fun defaultTemplateMakesArtistAlbumTrack() {
        assertEquals("Band/Album/03 - Song.flac", DownloadLayout.relativeName(DownloadLayout.DEFAULT_TEMPLATE, album, "flac"))
    }

    @Test
    fun aSingleWithNoAlbumGoesToSingles() {
        val single = Info(title = "Song", artist = "Artist")
        assertEquals("Artist/Singles/Song.m4a", DownloadLayout.relativeName(DownloadLayout.DEFAULT_TEMPLATE, single, "m4a"))
    }

    @Test
    fun illegalCharactersAndSlashesInNamesAreNeutralised() {
        val tricky = Info(title = "What/If? \"Yes\"", artist = "AC/DC", album = "A:B", track = 1)
        val name = DownloadLayout.relativeName(DownloadLayout.DEFAULT_TEMPLATE, tricky, "mp3")
        assertFalse(name.contains(":") || name.contains("?") || name.contains("\""))
        assertEquals(3, name.split('/').size)
    }

    @Test
    fun flatTemplateKeepsTheOldNaming() {
        assertEquals("Artist - Song.opus", DownloadLayout.relativeName(DownloadLayout.FLAT_TEMPLATE, album.copy(artist = "Artist"), "opus"))
    }

    @Test
    fun emptyTokensDoNotLeaveStraySeparators() {
        val noTrack = album.copy(track = null)
        assertEquals("Band/Album/Song.flac", DownloadLayout.relativeName(DownloadLayout.DEFAULT_TEMPLATE, noTrack, "flac"))
    }

    @Test
    fun anInvalidTemplateFallsBackToTheDefault() {
        listOf("", "/abs/{title}", "../{title}", "{bogus}/{title}", "{album}/", "no tokens").forEach {
            assertFalse(it, DownloadLayout.valid(it))
            assertEquals("Band/Album/03 - Song.flac", DownloadLayout.relativeName(it, album, "flac"))
        }
        assertTrue(DownloadLayout.valid("{artist}/{title}"))
    }

    @Test
    fun longNamesAreCutToWhatAFilesystemTakes() {
        val long = album.copy(title = "x".repeat(400))
        assertTrue(DownloadLayout.relativeName(DownloadLayout.DEFAULT_TEMPLATE, long, "flac").length <= 255)
    }

    @Test
    fun folderAndFileNamePartsSplitOnTheLastSlash() {
        assertEquals("Band/Album", DownloadLayout.folderOf("Band/Album/03 - Song.flac"))
        assertEquals("03 - Song.flac", DownloadLayout.fileNameOf("Band/Album/03 - Song.flac"))
        assertEquals("", DownloadLayout.folderOf("Song.flac"))
    }
}
