package dev.auden.library

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveFromSourceTest {
    private fun song(id: String, spotify: String? = null) =
        Song(videoId = id, title = id, artist = "a", thumbnailUrl = null, spotifyId = spotify)

    @Test
    fun spotifySyncDropsRemovedSongsKeepsOwnAdditionsAndTakesCover() {
        val source = "sp_test_" + System.nanoTime()
        LocalPlaylists.saveFromSource(source, "T", listOf(song("a", "sa"), song("b", "sb")), "c1", setOf("sa", "sb"))
        LocalPlaylists.add(LocalPlaylists.bySource(source)!!.id, listOf(song("mine")))

        val result = LocalPlaylists.saveFromSource(source, "T", listOf(song("a", "sa"), song("c", "sc")), "c2", setOf("sa", "sc"))

        assertEquals(1, result.removed)
        assertEquals(1, result.added)
        assertEquals(listOf("a", "mine", "c"), result.playlist.songs.map { it.videoId })
        assertEquals("c2", result.playlist.coverUrl)
        LocalPlaylists.delete(result.playlist.id)
    }
}
