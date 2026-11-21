package dev.auden.library

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongVersionIdentityTest {
    private fun song(title: String, artist: String, length: String? = "3:30", id: String = title + artist) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length)

    @Test
    fun theSameRecordingUnderDifferentIdsIsTheSameSong() {
        assertTrue(SongIdentity.same(song("Blinding Lights", "The Weeknd", id = "a"), song("Blinding Lights", "Weeknd", id = "b")))
        assertTrue(SongIdentity.same(song("Song (Remastered 2011)", "Band", id = "a"), song("Song", "Band", id = "b")))
        assertTrue(SongIdentity.same(song("Song (feat. Guest)", "Band", id = "a"), song("Song", "Band", id = "b")))
    }

    @Test
    fun aLiveAcousticOrRemixedTakeIsNotTheStudioSongHoweverTheLengthsFall() {
        // No length on the file: the brackets are all there is to tell them apart.
        assertFalse(SongIdentity.same(song("Song", "Band", "3:30", "a"), song("Song (Live)", "Band", null, "b")))
        assertFalse(SongIdentity.same(song("Song", "Band", null, "a"), song("Song - Acoustic", "Band", null, "b")))
        assertFalse(SongIdentity.same(song("Song (Sped Up)", "Band", null, "a"), song("Song", "Band", null, "b")))
        assertTrue(SongIdentity.same(song("Song (Live)", "Band", null, "a"), song("Song [Live]", "Band", null, "b")))
    }

    @Test
    fun anArtistWhoseNameIsPartOfAnotherIsNotTheSameArtist() {
        assertFalse(SongIdentity.same(song("Hello", "Ed", null, "a"), song("Hello", "Ed Sheeran", null, "b")))
        assertFalse(SongIdentity.same(song("Hello", "Sia", null, "a"), song("Hello", "Siamese Dream", null, "b")))
        assertTrue(SongIdentity.same(song("Hello", "Sia", null, "a"), song("Hello", "Sia & Diplo", null, "b")))
    }

    @Test
    fun differentLengthsStillSeparateTwoSongsWithTheSameTitle() {
        assertFalse(SongIdentity.same(song("Intro", "Band", "1:00", "a"), song("Intro", "Band", "3:30", "b")))
    }
}
