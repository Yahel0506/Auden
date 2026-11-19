package dev.auden.library

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saving a source a second time (phase 5). It used to double as a sync that also dropped what a Spotify page had
 * lost; that merge is [PlaylistMerge]'s now, through "Refresh from source" (see `PlaylistMergeTest` and
 * `PlaylistRefresherTest`). What is left here — for callers that hold songs but no listing to compare with, a
 * pasted link — only tops the copy up: nothing is ever taken out.
 */
class SaveFromSourceTest {
    private fun song(id: String, spotify: String? = null) =
        Song(videoId = id, title = id, artist = "a", thumbnailUrl = null, spotifyId = spotify)

    @Test
    fun savingAgainOnlyAddsKeepsOwnAdditionsAndTakesCover() {
        val source = "sp_test_" + System.nanoTime()
        val first = LocalPlaylists.saveFromSource(source, "T", listOf(song("a", "sa"), song("b", "sb")), "c1")
        assertTrue(first.created)
        LocalPlaylists.add(first.playlist.id, listOf(song("mine")))

        // "b" is gone from the source: a save does not know that it was the source's, so it stays.
        val result = LocalPlaylists.saveFromSource(source, "T", listOf(song("a", "sa"), song("c", "sc")), "c2")

        assertEquals(false, result.created)
        assertEquals(0, result.removed)
        assertEquals(1, result.added)
        assertEquals(listOf("a", "b", "mine", "c"), result.playlist.songs.map { it.videoId })
        assertEquals("c2", result.playlist.coverUrl)
        assertEquals(first.playlist.id, LocalPlaylists.bySource(source)?.id)
        LocalPlaylists.delete(result.playlist.id)
    }
}
