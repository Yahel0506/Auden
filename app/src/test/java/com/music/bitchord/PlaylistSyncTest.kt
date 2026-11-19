package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.PlaylistSync
import com.music.bitchord.download.SavedCollection
import dev.auden.library.LocalPlaylists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What decides which tracks a playlist check fetches. All of it fails quietly: a
 * listing cut short is a song that never arrives, and a wrong answer to "is this
 * new" brings back a song the listener deleted.
 */
class PlaylistSyncTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    private fun songs(vararg ids: String) = ids.map(::song)

    private fun record(id: String = "VLPL1", vararg ids: String, playlist: Boolean = true) =
        SavedCollection(id = id, title = id, playlist = playlist, videoIds = ids.toList())

    private fun ids(list: List<Song>) = list.map { it.videoId }

    // The worker no longer reads YouTube Music (phase 5): a YouTube Music playlist downloaded under its own id is
    // followed through its library copy, which only the listener refreshes from the source.

    @Test
    fun `a YouTube playlist downloaded under its own id is followed through its library copy`() {
        val source = "PLsynctest" + System.nanoTime()
        val copy = LocalPlaylists.saveFromSource(source, "Road", songs("a", "b")).playlist
        try {
            assertEquals(copy.id, PlaylistSync.devicePlaylistOf(record("VL$source", "a"))?.id)
            assertEquals(copy.id, PlaylistSync.devicePlaylistOf(record(source, "a"))?.id)
            // What the copy gained is what the record has not named yet; nothing is asked of YouTube for it.
            LocalPlaylists.add(copy.id, songs("c"))
            val device = PlaylistSync.devicePlaylistOf(record("VL$source", "a", "b"))!!
            assertEquals(listOf("c"), ids(PlaylistSync.newTracks(PlaylistSync.downloadable(device.songs), record("VL$source", "a", "b"))))
        } finally {
            LocalPlaylists.delete(copy.id)
        }
    }

    @Test
    fun `a downloaded album or Liked Music has no copy to follow`() {
        assertNull(PlaylistSync.devicePlaylistOf(record("MPREb_1", playlist = false)))
        assertNull(PlaylistSync.devicePlaylistOf(record("VLLM")))
    }

    @Test
    fun `only tracks the record has never named are new`() {
        // "b" was downloaded and later deleted, so it is named but not on disk.
        val fresh = PlaylistSync.newTracks(songs("a", "b", "c"), record("VLPL1", "a", "b"))
        assertEquals(listOf("c"), ids(fresh))
    }

    @Test
    fun `a track that failed is left out of the record so it is tried again`() {
        val listing = songs("a", "b", "c")
        val order = PlaylistSync.listingToRecord(listing, record("VLPL1", "a"), handled = setOf("c"))
        assertEquals(listOf("a", "c"), ids(order))
    }

    @Test
    fun `only a YouTube playlist is checked, under the id YouTube answers to`() {
        assertEquals("VLPL1", PlaylistSync.syncableBrowseId(record("VLPL1")))
        assertEquals("VLPL1", PlaylistSync.syncableBrowseId(record("PL1")))
        assertNull(PlaylistSync.syncableBrowseId(record("MPREb_1", playlist = false)))
        assertNull(PlaylistSync.syncableBrowseId(record("VLOLAK1", playlist = false)))
        assertNull(PlaylistSync.syncableBrowseId(record("local:playlist:sp_local_1")))
        assertNull(PlaylistSync.syncableBrowseId(record("spotify:playlist:1")))
    }

    @Test
    fun `deleting a release keeps the tracks another release lists`() {
        val collections = mapOf(
            "one" to record("one", "a", "b", "c"),
            "two" to record("two", "b"),
        )
        val saved = mapOf("a" to "file:a", "b" to "file:b", "c" to "file:c")
        assertEquals(listOf("a", "c"), Downloads.tracksOnlyIn("one", collections, saved))
    }

    @Test
    fun `one file under two ids counts as shared`() {
        // "video" was swapped for "track" on the way down: both name one file.
        val collections = mapOf(
            "one" to record("one", "video", "a"),
            "two" to record("two", "track"),
        )
        val saved = mapOf("video" to "file:x", "track" to "file:x", "a" to "file:a")
        assertEquals(listOf("a"), Downloads.tracksOnlyIn("one", collections, saved))
    }

    @Test
    fun `a device playlist is not the kind a YouTube check can read`() {
        assertNull(PlaylistSync.syncableBrowseId(record("local:playlist:sp_local_1")))
        assertNull(PlaylistSync.devicePlaylistOf(record("VLPL1")))
        assertNull(PlaylistSync.devicePlaylistOf(record("local:playlist:sp_local_1", playlist = false)))
        // Recognised as a device playlist by its id; with none stored under it, there is nothing to read.
        assertNull(PlaylistSync.devicePlaylistOf(record("local:playlist:sp_local_1")))
    }

    @Test
    fun `files already on the device are never queued for download`() {
        val onDevice = Song(videoId = "file:///a.mp3", title = "a", artist = "x", thumbnailUrl = null, localUri = "file:///a.mp3")
        assertEquals(listOf("b"), ids(PlaylistSync.downloadable(listOf(onDevice, song("b"), song(""))))) 
    }
}
