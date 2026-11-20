package dev.auden.transfer

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.SpotifyException
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.ImportedOrigin
import dev.auden.library.PlaylistMerge
import dev.auden.library.PlaylistMerge.Counts
import dev.auden.library.PlaylistMerge.Mode
import dev.auden.library.Service
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaylistRefresherTest {

    /** The library in memory; [beforeApply] runs just before a write, to play an edit made meanwhile. */
    private class FakeStore : RefreshStore {
        val playlists = linkedMapOf<String, LocalPlaylist>()
        val origins = mutableMapOf<String, ImportedOrigin>()
        var writes = 0
        var beforeApply: (() -> Unit)? = null

        fun put(id: String, sourceId: String?, songs: List<Song>, snapshot: List<String>? = null, syncedAt: Long? = null) {
            playlists[id] = LocalPlaylist(id, "T-$id", songs, sourceId)
            if (snapshot != null || syncedAt != null) origins[id] = ImportedOrigin(snapshot, syncedAt, "Origin $id")
        }

        override suspend fun playlist(id: String) = playlists[id]
        override suspend fun playlists() = playlists.values.toList()
        override suspend fun origin(playlistId: String) = origins[playlistId]

        override suspend fun apply(playlistId: String, update: (LocalPlaylist) -> Pair<List<Song>, ImportedOrigin>?): LocalPlaylist? {
            beforeApply?.invoke()
            val current = playlists[playlistId] ?: return null
            val (songs, origin) = update(current) ?: return null
            writes++
            return current.copy(songs = songs).also {
                playlists[playlistId] = it
                origins[playlistId] = origin
            }
        }
    }

    /** A source that only counts what is read from it, failing with [failure] when set, waiting on [gate] when set. */
    private class Source(
        override val service: Service,
        val lists: MutableMap<String, List<RemoteTrack>> = mutableMapOf(),
    ) : MusicSource {
        val reads = mutableListOf<String>()
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        val inFlight = AtomicInteger()
        var maxInFlight = 0

        override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) = error("a refresh never reads likes")
        override suspend fun playlists() = error("a refresh never lists playlists")
        override suspend fun albums() = error("a refresh never lists albums")
        override suspend fun artists() = error("a refresh never lists artists")

        override suspend fun playlistTracks(sourceId: String): List<RemoteTrack> {
            val now = inFlight.incrementAndGet()
            maxInFlight = maxOf(maxInFlight, now)
            try {
                reads += sourceId
                gate?.await()
                failure?.let { throw it }
                return lists[sourceId] ?: throw IncompleteReadException("no such list $sourceId")
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private class CountingResolver(private val misses: Set<String> = emptySet()) : ImportResolver {
        val asked = mutableListOf<String>()
        override suspend fun song(track: SpotifyTrack): Song? {
            asked += track.id
            return if (track.id in misses) null else Song(videoId = "yt-${track.id}", title = track.title, artist = track.artist, thumbnailUrl = null)
        }
        override suspend fun ytAlbum(album: RemoteCollection): RemoteCollection? = null
        override suspend fun ytArtistId(artist: RemoteArtist): String? = null
    }

    private val store = FakeStore()
    private val yt = Source(Service.YT_MUSIC)
    private val spotify = Source(Service.SPOTIFY)
    private val resolver = CountingResolver(misses = setOf("s-miss"))
    private var ytSignedIn = true
    private var spotifySignedIn = true
    private var clock = 1_000L

    private fun refresher() = PlaylistRefresher(
        store = store,
        source = { if (it == Service.SPOTIFY) spotify else yt },
        resolver = resolver,
        connected = { if (it == Service.SPOTIFY) spotifySignedIn else ytSignedIn },
        now = { clock },
        pauseMs = 0,
    )

    private fun songs(vararg ids: String) = ids.map { song(it) }
    private fun ids(id: String) = store.playlists.getValue(id).songs.map { it.videoId }
    private fun reason(result: Result<*>) = (result.exceptionOrNull() as RefreshException).reason

    // ---- Preview and refresh -------------------------------------------------------------------------------

    @Test
    fun aPreviewReadsAndWritesNothingAndTheRefreshItConfirmsReusesTheRead() = runBlocking {
        // The listener removed b and added "mine"; the source gained c.
        store.put("p1", "PL1", songs("a", "mine"), snapshot = listOf("a", "b"), syncedAt = 10)
        yt.lists["PL1"] = yt("a", "b", "c")
        val refresher = refresher()

        val preview = refresher.preview("p1").getOrThrow()

        assertEquals(Counts(added = 1, keptLocal = 1, unchanged = 1, stayedRemoved = 1), preview.merge)
        assertEquals(Counts(added = 2, removed = 1, unchanged = 1), preview.replace)
        assertTrue(preview.mergeChanges)
        assertFalse(preview.firstRefresh)
        assertEquals(10L, preview.syncedAt)
        assertEquals("Origin p1", preview.originTitle)
        assertEquals(0, store.writes)

        clock = 2_000L
        val outcome = refresher.refresh("p1", Mode.MERGE).getOrThrow()

        assertEquals(listOf("a", "c", "mine"), ids("p1"))
        assertEquals(1, outcome.counts.added)
        assertEquals(listOf("a", "b", "c"), store.origins.getValue("p1").snapshot)
        assertEquals(2_000L, store.origins.getValue("p1").syncedAt)
        assertEquals("Origin p1", store.origins.getValue("p1").originTitle)
        assertEquals(listOf("PL1"), yt.reads)
        assertEquals(1, store.writes)
    }

    @Test
    fun replaceMakesTheCopyTheSourceAndMovesTheSnapshot() = runBlocking {
        store.put("p1", "PL1", songs("b", "mine"), snapshot = listOf("a", "b"))
        yt.lists["PL1"] = yt("a", "b", "c")

        val outcome = refresher().refresh("p1", Mode.REPLACE).getOrThrow()

        assertEquals(listOf("a", "b", "c"), ids("p1"))
        assertEquals(Counts(added = 2, removed = 1, unchanged = 1), outcome.counts)
        assertEquals(listOf("a", "b", "c"), store.origins.getValue("p1").snapshot)
    }

    @Test
    fun withoutASnapshotTheFirstRefreshOnlyAddsAndThenRecordsOne() = runBlocking {
        // A copy from before origins were kept: no origin row at all.
        store.put("p1", "PL1", songs("gone", "a"))
        yt.lists["PL1"] = yt("a", "b")
        val refresher = refresher()

        assertTrue(refresher.preview("p1").getOrThrow().firstRefresh)
        refresher.refresh("p1").getOrThrow()

        assertEquals(listOf("gone", "a", "b"), ids("p1"))
        assertEquals(listOf("a", "b"), store.origins.getValue("p1").snapshot)
        assertEquals("T-p1", store.origins.getValue("p1").originTitle)
    }

    @Test
    fun aRefreshThatChangesNothingStillRecordsWhenTheSourceWasRead() = runBlocking {
        store.put("p1", "PL1", songs("a"), snapshot = listOf("a"), syncedAt = 5)
        yt.lists["PL1"] = yt("a")
        clock = 9_000L

        val outcome = refresher().refresh("p1").getOrThrow()

        assertFalse(outcome.changed)
        assertEquals(9_000L, store.origins.getValue("p1").syncedAt)
    }

    @Test
    fun aFirstRefreshWithNothingToChangeStillRecordsASnapshotSoALocalRemovalStaysRemoved() = runBlocking {
        // A copy from before snapshots, equal to its source.
        store.put("p1", "PL1", songs("a", "b"))
        yt.lists["PL1"] = yt("a", "b")
        val refresher = refresher()
        val first = refresher.preview("p1").getOrThrow()
        assertTrue(first.firstRefresh)
        assertFalse(first.mergeChanges)

        refresher.refresh("p1").getOrThrow()
        assertEquals(listOf("a", "b"), store.origins.getValue("p1").snapshot)

        // The listener removes b, which the source still holds: it is not offered back.
        store.playlists["p1"] = store.playlists.getValue("p1").copy(songs = songs("a"))
        val second = refresher().preview("p1").getOrThrow()
        assertFalse(second.mergeChanges)
        assertEquals(1, second.merge.stayedRemoved)
    }

    @Test
    fun anEditMadeWhileTheSourceWasReadIsMergedNotLost() = runBlocking {
        store.put("p1", "PL1", songs("a"), snapshot = listOf("a"))
        yt.lists["PL1"] = yt("a", "b")
        store.beforeApply = {
            val p = store.playlists.getValue("p1")
            store.playlists["p1"] = p.copy(songs = p.songs + song("added-meanwhile"))
        }

        refresher().refresh("p1").getOrThrow()

        assertEquals(listOf("a", "b", "added-meanwhile"), ids("p1"))
    }

    @Test
    fun aYouTubeAlbumsNewSongsNameTheAlbum() = runBlocking {
        store.put("p1", "MPREb_1", songs("a"), snapshot = listOf("a"))
        yt.lists["MPREb_1"] = yt("a", "b")

        refresher().refresh("p1").getOrThrow()

        assertEquals("T-p1", store.playlists.getValue("p1").songs.last().albumName)
    }

    // ---- Spotify ---------------------------------------------------------------------------------------------

    @Test
    fun spotifyTracksAreMatchedOnlyWhenNewAndTheUnmatchedAreReportedNotAdded() = runBlocking {
        val id = "spotify:playlist:gym"
        store.put("p1", id, listOf(song("yt-s1", spotify = "s1"), song("mine")), snapshot = listOf("s1"))
        spotify.lists[id] = sp("s1", "s2", "s-miss")

        val outcome = refresher().refresh("p1").getOrThrow()

        // s1 is in the copy already: not searched for again.
        assertEquals(listOf("s2", "s-miss"), resolver.asked)
        assertEquals(listOf("yt-s1", "yt-s2", "mine"), ids("p1"))
        assertEquals("s2", store.playlists.getValue("p1").songs[1].spotifyId)
        assertEquals(listOf(UnmatchedTrack("s-s-miss", "sa", "T-p1")), outcome.unmatched)
        assertEquals(1, outcome.counts.unmatched)
        assertEquals(listOf("s1", "s2"), store.origins.getValue("p1").snapshot)
    }

    @Test
    fun aSpotifyCopyWithoutAConnectedAccountIsNotReadAtAll() = runBlocking {
        spotifySignedIn = false
        store.put("p1", "spotify:playlist:gym", songs("a"))

        val result = refresher().preview("p1")

        assertEquals(RefreshError.NotConnected(Service.SPOTIFY), reason(result))
        assertTrue(spotify.reads.isEmpty())
    }

    @Test
    fun aSpotifySessionSpotifyRefusesIsExpired() = runBlocking {
        store.put("p1", "spotify:playlist:gym", songs("a"))
        spotify.failure = SpotifyException(SpotifyException.Kind.EXPIRED, "expired")

        assertEquals(RefreshError.Expired(Service.SPOTIFY), reason(refresher().refresh("p1")))
        assertEquals(0, store.writes)
    }

    // ---- What cannot be refreshed ---------------------------------------------------------------------------

    @Test
    fun copiesWithNoReadableSourceSayWhyWithoutReading() = runBlocking {
        store.put("made", null, songs("a"))
        store.put("likes", "LM", songs("a"))
        store.put("album", "spotify:album:x", songs("a"))
        val refresher = refresher()

        assertEquals(RefreshError.NotFound, reason(refresher.refresh("nope")))
        assertEquals(RefreshError.NoOrigin, reason(refresher.refresh("made")))
        assertEquals(RefreshError.LikesList, reason(refresher.refresh("likes")))
        assertEquals(RefreshError.NoListing, reason(refresher.refresh("album")))
        assertTrue(yt.reads.isEmpty() && spotify.reads.isEmpty())
        assertFalse(PlaylistRefresher.canRefresh(null))
        assertFalse(PlaylistRefresher.canRefresh("LM"))
        assertFalse(PlaylistRefresher.canRefresh("spotify:album:x"))
        assertTrue(PlaylistRefresher.canRefresh("PL1"))
        assertTrue(PlaylistRefresher.canRefresh("MPREb_1"))
        assertTrue(PlaylistRefresher.canRefresh("spotify:playlist:x"))
    }

    @Test
    fun aSourceThatReadsBackEmptyChangesNothing() = runBlocking {
        store.put("p1", "PL1", songs("a", "b"), snapshot = listOf("a", "b"))
        yt.lists["PL1"] = emptyList()

        assertEquals(RefreshError.EmptySource(Service.YT_MUSIC), reason(refresher().refresh("p1")))
        assertEquals(listOf("a", "b"), ids("p1"))
        assertEquals(0, store.writes)
    }

    @Test
    fun aYouTubeListThatFailsSaysNotConnectedWithoutAnAccountAndFailedWithOne() = runBlocking {
        store.put("p1", "PL1", songs("a"))
        yt.failure = IncompleteReadException("first page failed")

        ytSignedIn = false
        assertEquals(RefreshError.NotConnected(Service.YT_MUSIC), reason(refresher().refresh("p1")))
        ytSignedIn = true
        assertTrue(reason(refresher().refresh("p1")) is RefreshError.Failed)
        yt.failure = IncompleteReadException("wrapped", Innertube.NotSignedInException())
        assertEquals(RefreshError.NotConnected(Service.YT_MUSIC), reason(refresher().refresh("p1")))
        assertEquals(0, store.writes)
    }

    // ---- One at a time ---------------------------------------------------------------------------------------

    @Test
    fun twoRefreshesOfOnePlaylistRunOneAfterTheOther() = runBlocking {
        store.put("p1", "PL1", songs("a"), snapshot = listOf("a"))
        yt.lists["PL1"] = yt("a", "b")
        val gate = CompletableDeferred<Unit>()
        yt.gate = gate
        val refresher = refresher()

        val first = async { refresher.refresh("p1", Mode.MERGE) }
        val second = async { refresher.refresh("p1", Mode.MERGE) }
        repeat(20) { yield() }
        assertEquals(setOf("p1"), refresher.refreshing.value)
        gate.complete(Unit)
        val one = first.await().getOrThrow()
        val two = second.await().getOrThrow()

        assertEquals(1, yt.maxInFlight)
        // The second ran on what the first wrote: nothing new, nothing added twice.
        assertEquals(1, one.counts.added)
        assertEquals(0, two.counts.added)
        assertEquals(listOf("a", "b"), ids("p1"))
        assertTrue(refresher.refreshing.value.isEmpty())
    }

    // ---- All of them ---------------------------------------------------------------------------------------

    @Test
    fun refreshAllGoesThroughEveryCopyAndAsksARefusingServiceOnce() = runBlocking {
        store.put("s1", "spotify:playlist:one", songs("a"))
        store.put("s2", "spotify:playlist:two", songs("a"))
        store.put("y1", "PL1", songs("a"), snapshot = listOf("a"))
        store.put("made", null, songs("a"))
        store.put("likes", "LM", songs("a"))
        spotify.failure = SpotifyException(SpotifyException.Kind.SIGNED_OUT, "signed out")
        yt.lists["PL1"] = yt("a", "b")
        val seen = mutableListOf<String>()

        val items = refresher().refreshAll { _, _, title -> if (title.isNotEmpty()) seen += title }

        assertEquals(listOf("s1", "s2", "y1"), items.map { it.playlistId })
        assertEquals(listOf("T-s1", "T-s2", "T-y1"), seen)
        assertEquals(RefreshError.NotConnected(Service.SPOTIFY), reason(items[0].result))
        assertEquals(RefreshError.NotConnected(Service.SPOTIFY), reason(items[1].result))
        assertEquals(listOf("spotify:playlist:one"), spotify.reads)
        assertEquals(listOf("a", "b"), ids("y1"))
        assertTrue(items[2].result.isSuccess)
    }

    // ---- Status ----------------------------------------------------------------------------------------------

    @Test
    fun theStatusLineReadsNothingFromTheSource() = runBlocking {
        store.put("p1", "PL1", songs("a", "mine"), snapshot = listOf("a", "b"), syncedAt = 77)
        store.put("old", "PL2", songs("a"))
        store.put("made", null, songs("a"))
        val refresher = refresher()

        val status = refresher.status("p1")!!
        assertEquals(77L, status.syncedAt)
        assertNull(status.blocked)
        assertEquals(PlaylistMerge.LocalChanges(added = 1, removed = 1), status.localChanges)
        assertNull(refresher.status("old")!!.localChanges)
        assertEquals(RefreshError.NoOrigin, refresher.status("made")!!.blocked)
        assertTrue(yt.reads.isEmpty())
    }
}
