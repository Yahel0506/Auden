package dev.auden.transfer

import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.model.Song
import dev.auden.library.LocalPlaylists
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real stores' batch paths, in memory (neither is given a database or preferences here). */
class LocalStoresImportTest {
    private val prefix = "t" + System.nanoTime() + "_"
    private fun id(name: String) = prefix + name

    @After
    fun tearDown() {
        // Liked is a process-wide store: leave it as it was found.
        OfflinePlaylists.playlists.value.first { it.isLiked }.songs
            .filter { it.videoId.startsWith(prefix) || it.localUri?.startsWith(prefix) == true }
            .forEach { OfflinePlaylists.toggleLike(it) }
        LocalPlaylists.playlists.value.filter { it.sourceId?.contains(prefix) == true }.forEach { LocalPlaylists.delete(it.id) }
    }

    private fun liked(): List<String> =
        OfflinePlaylists.playlists.value.first { it.isLiked }.songs.map { it.videoId }.filter { it.startsWith(prefix) }

    @Test
    fun appendLikesGoesAfterWhatIsLikedInOrderAndOnlyOnce() {
        OfflinePlaylists.toggleLike(song(id("mine")))
        val file = Song(videoId = id("file"), title = "f", artist = "a", thumbnailUrl = null, localUri = id("content-file"))
        OfflinePlaylists.toggleLike(file)

        val added = OfflinePlaylists.appendLikes(listOf(song(id("a")), song(id("mine")), song(id("b")), song(id("a")), song(id("file"))))

        assertEquals(2, added)
        assertEquals(listOf(id("file"), id("mine"), id("a"), id("b")), liked())
        assertTrue(OfflinePlaylists.isLiked(song(id("b"))))
        assertEquals(0, OfflinePlaylists.appendLikes(listOf(song(id("b")))))
    }

    @Test
    fun aPastedLinkSavedTwiceTopsUpOneCopy() {
        val source = "spotify:playlist:" + id("link")
        val first = LinkImports.keep("Gym", listOf(song(id("a"))), source)
        val second = LinkImports.keep("Gym again", listOf(song(id("a")), song(id("b"))), source)

        assertEquals(first.id, second.id)
        assertEquals(source, second.sourceId)
        assertEquals(listOf(id("a"), id("b")), LocalPlaylists.get(first.id)!!.songs.map { it.videoId })
        assertEquals(1, LocalPlaylists.playlists.value.count { it.sourceId == source })
    }
}
