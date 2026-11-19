package dev.auden.transfer

import com.music.bitchord.data.YtMusicRepository.SongPage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.spotify.SpotifyAlbum
import com.music.bitchord.data.spotify.SpotifyPlaylist
import dev.auden.library.OriginKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MusicSourceTest {
    private fun page(vararg ids: String, next: String? = null) = SongPage(ids.map { song(it) }, next)

    private fun card(browseId: String?, title: String = browseId.orEmpty()) =
        ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = browseId)

    private fun ytSource(
        pages: Map<String, Result<SongPage>> = emptyMap(),
        first: Result<SongPage> = Result.success(page()),
        playlists: List<ShelfItem> = emptyList(),
        albums: List<ShelfItem> = emptyList(),
    ) = YtMusicSource(
        firstPage = { first },
        nextPage = { pages[it] ?: Result.failure(IllegalStateException("no page $it")) },
        playlistCards = { Result.success(playlists) },
        albumCards = { Result.success(albums) },
        subscriptionCards = { Result.success(emptyList()) },
    )

    @Test
    fun likedMusicIsReadToItsEndEachSongOnce() = runBlocking {
        val source = ytSource(
            first = Result.success(page("a", "b", next = "t1")),
            pages = mapOf("t1" to Result.success(page("b", "c", next = "t2")), "t2" to Result.success(page("d"))),
        )
        val got = mutableListOf<List<String>>()
        source.likedSongs { got += it.map(RemoteTrack::remoteId) }
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), got)
    }

    @Test
    fun aFeedThatLoopsBackEndsTheReadAsComplete() = runBlocking {
        val source = ytSource(
            first = Result.success(page("a", next = "t1")),
            pages = mapOf("t1" to Result.success(page("b", next = "t1"))),
        )
        val got = mutableListOf<String>()
        source.likedSongs { page -> page.mapTo(got) { it.remoteId } }
        assertEquals(listOf("a", "b"), got)
    }

    @Test
    fun aFailedPageIsAnIncompleteReadNotAShortList() = runBlocking {
        val source = ytSource(first = Result.success(page("a", next = "t1")))
        try {
            source.likedSongs { }
            fail("a failed page must not end the read quietly")
        } catch (e: IncompleteReadException) {
            assertTrue(e.message!!.contains("VLLM"))
        }
        try {
            ytSource(first = Result.failure(IllegalStateException("offline"))).playlistTracks("PL1")
            fail("a failed first page too")
        } catch (_: IncompleteReadException) {
        }
    }

    @Test
    fun youTubePlaylistsLeaveOutLikedMusicFeedsAndChannels() = runBlocking {
        val source = ytSource(
            playlists = listOf(card("VLLM", "Liked Music"), card("VLPL1", "Mine"), card("FEmusic_liked_videos"), card("UCx"), card(null), card("VLPL1")),
            albums = listOf(card("MPREb_1", "Blue"), card("VLLM")),
        )
        val playlists = source.playlists()
        assertEquals(listOf("PL1"), playlists.map { it.sourceId })
        assertEquals(OriginKind.PLAYLIST, playlists.single().kind)
        assertEquals(listOf("MPREb_1"), source.albums().map { it.sourceId })
        assertEquals(OriginKind.ALBUM, source.albums().single().kind)
    }

    @Test
    fun spotifyPlaylistsLeaveOutLikedSongsAndAlbumsHaveNoListing() = runBlocking {
        val source = SpotifySource(
            playlistList = { listOf(SpotifyPlaylist("liked", "Liked Songs", null, null), SpotifyPlaylist("abc", "Gym", "me", null)) },
            albumList = { listOf(SpotifyAlbum("alb", "Blue", "Joni", null)) },
            artistList = { emptyList() },
            tracksOf = { id -> if (id == "liked") (1..250).map { spotifyTrack("s$it") } else listOf(spotifyTrack("p1")) },
        )
        assertEquals(listOf("spotify:playlist:abc"), source.playlists().map { it.sourceId })
        assertEquals(listOf("spotify:album:alb"), source.albums().map { it.sourceId })
        assertEquals(listOf("p1"), source.playlistTracks("spotify:playlist:abc").map { it.remoteId })
        try {
            source.playlistTracks("spotify:album:alb")
            fail("Spotify gives no album listing")
        } catch (_: UnsupportedOperationException) {
        }
        val pages = mutableListOf<Int>()
        source.likedSongs { pages += it.size }
        assertEquals(listOf(100, 100, 50), pages)
    }

    @Test
    fun albumMatchIsStrictAboutTitleAndArtist() {
        fun album(id: String, title: String, subtitle: String, type: BrowseType = BrowseType.ALBUM) = BrowseItem(id, title, subtitle, null, type)
        val candidates = listOf(
            album("MPREb_live", "Blue (Live)", "Album • Joni Mitchell • 1999"),
            album("VLPLx", "Blue", "Playlist • Joni Mitchell", BrowseType.PLAYLIST),
            album("MPREb_other", "Blue", "Album • Someone Else"),
            album("MPREb_blue", "Blue", "Album • Joni Mitchell • 1971"),
        )
        assertEquals("MPREb_blue", AlbumMatch.pick(candidates, "Blue", "Joni Mitchell")?.browseId)
        // An edition in brackets is the same album.
        assertEquals("MPREb_blue", AlbumMatch.pick(candidates, "Blue (Remastered 2021)", "Joni Mitchell")?.browseId)
        assertEquals("MPREb_x", AlbumMatch.pick(listOf(album("MPREb_x", "Café Tacvba", "Album • Café Tacvba")), "Cafe Tacvba", "Café Tacvba")?.browseId)
        assertNull(AlbumMatch.pick(candidates, "Court and Spark", "Joni Mitchell"))
        assertNull(AlbumMatch.pick(candidates, "Blue", "Nobody"))
    }
}
