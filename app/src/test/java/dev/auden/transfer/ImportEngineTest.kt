package dev.auden.transfer

import dev.auden.library.OriginKind
import dev.auden.library.Service
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportEngineTest {
    private val library = FakeLibrary()
    private val catalog = FakeSource(Service.YT_MUSIC)
    private fun engine(resolver: ImportResolver = FakeResolver(), source: MusicSource = catalog) =
        ImportEngine(library, resolver, source, now = { 1_000L })

    private fun playlist(sourceId: String, service: Service = Service.YT_MUSIC, kind: OriginKind = OriginKind.PLAYLIST, title: String = sourceId) =
        RemoteCollection(sourceId, service, kind, title, thumbnailUrl = "https://cover/$sourceId")

    // ---- Likes ---------------------------------------------------------------

    @Test
    fun likesGoAfterTheDevicesOwnInTheSourcesOrderOnePageAWrite() = runBlocking {
        library.likes += song("mine")
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("n1", "n2"), yt("n3", "mine", "n4")))

        val report = engine().importLikes(source)

        assertEquals(listOf("mine", "n1", "n2", "n3", "n4"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 4, skipped = 1), report.likes)
        assertEquals(2, library.appendCalls)
        assertTrue(report.complete)
    }

    @Test
    fun likesUnLikedHereAreNotBroughtBack() = runBlocking {
        library.hidden += "gone"
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("a", "gone", "b")))

        val report = engine().importLikes(source)

        assertEquals(listOf("a", "b"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 2, skipped = 1), report.likes)
    }

    @Test
    fun aTransferTheListenerAsksForBringsBackWhatTheyUnlikedHere() = runBlocking {
        // They imported, un-liked "gone" by hand (it is now hidden), and import again: they asked for the likes.
        library.hidden += "gone"
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("a", "gone", "b")))

        val report = engine().importLikes(source, honorHidden = false)

        assertEquals(listOf("a", "gone", "b"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 3), report.likes)
        assertTrue("gone" !in library.hidden)
    }

    @Test
    fun importingTheSameLikesTwiceAddsNothingTheSecondTime() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("a", "b"), yt("c")))
        engine().importLikes(source)
        val again = engine().importLikes(source)
        assertEquals(listOf("a", "b", "c"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 0, skipped = 3), again.likes)
    }

    @Test
    fun spotifyLikesWithoutAMatchAreReportedAndNotImported() = runBlocking {
        val source = FakeSource(Service.SPOTIFY, likePages = listOf(sp("s1", "s2", "s3")))

        val report = engine(FakeResolver(misses = setOf("s2"))).importLikes(source)

        assertEquals(listOf("yt-s1", "yt-s3"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 2, unmatched = 1), report.likes)
        assertEquals(listOf(UnmatchedTrack("s-s2", "sa", null)), report.unmatched)
    }

    @Test
    fun aFailedPageKeepsWhatWasStoredAndSaysSo() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("a"), yt("b")), failAtPage = 1)

        val report = engine().importLikes(source)

        assertEquals(listOf("a"), library.likes.map { it.videoId })
        assertFalse(report.complete)
        assertEquals(ImportStep.LIKES, report.failed.single().step)
    }

    // ---- Playlists and albums -------------------------------------------------

    @Test
    fun aPlaylistBecomesACopyThatRecordsItsOrigin() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, tracks = mapOf("PL1" to yt("a", "b", "a")))

        val report = engine().importCollection(source, playlist("PL1", title = "Road"))

        val copy = library.copies.values.single()
        assertEquals("PL1", copy.sourceId)
        assertEquals("Road", copy.title)
        assertEquals(listOf("a", "b"), copy.songs.map { it.videoId })
        assertEquals(listOf("a", "b", "a"), copy.origin.snapshot)
        assertEquals(1_000L, copy.origin.syncedAt)
        assertEquals("Road", copy.origin.originTitle)
        assertEquals(Tally(added = 1), report.playlists)
        assertEquals(listOf(copy.id), report.created)
    }

    @Test
    fun aSourceAlreadyCopiedIsSkippedAndNotRead() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, tracks = mapOf("PL1" to yt("a")))
        engine().importCollection(source, playlist("PL1"))

        val again = engine().importCollection(source, playlist("PL1"))

        assertEquals(1, library.copies.size)
        assertEquals(Tally(skipped = 1), again.playlists)
        assertEquals(listOf("PL1"), source.tracksRead)
    }

    @Test
    fun theLikesListIsNeverImportedAsAPlaylist() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("a")), tracks = mapOf("LM" to yt("a")))

        val report = engine().importCollection(source, playlist("LM"))

        assertTrue(library.copies.isEmpty())
        assertEquals(listOf("a"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 1), report.likes)
        assertEquals(Tally(), report.playlists)
    }

    @Test
    fun spotifyPlaylistSnapshotHoldsOnlyWhatTheCopyTookIn() = runBlocking {
        // Phase 5: an unmatched track is left out of the snapshot, so a refresh adds it once a match is found
        // rather than taking it for a song the listener removed (PlaylistMerge).
        val id = "spotify:playlist:abc"
        val source = FakeSource(Service.SPOTIFY, tracks = mapOf(id to sp("s1", "s2", "s3")))

        val report = engine(FakeResolver(misses = setOf("s2"))).importCollection(source, playlist(id, Service.SPOTIFY, title = "Gym"))

        val copy = library.copies.values.single()
        assertEquals(listOf("yt-s1", "yt-s3"), copy.songs.map { it.videoId })
        assertEquals(listOf("s1", "s3"), copy.origin.snapshot)
        assertEquals(listOf(UnmatchedTrack("s-s2", "sa", "Gym")), report.unmatched)
    }

    @Test
    fun aYouTubeAlbumsSongsNameTheAlbum() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, tracks = mapOf("MPREb_1" to yt("a")))

        val report = engine().importCollection(source, playlist("MPREb_1", kind = OriginKind.ALBUM, title = "Blue"))

        val copy = library.copies.values.single()
        assertEquals("Blue", copy.songs.single().albumName)
        assertEquals("https://cover/MPREb_1", copy.songs.single().thumbnailUrl)
        assertEquals(Tally(added = 1), report.albums)
    }

    @Test
    fun aSpotifyAlbumIsCopiedAsTheYouTubeAlbumItMatchesOrReportedUnmatched() = runBlocking {
        val ytAlbum = playlist("MPREb_9", kind = OriginKind.ALBUM, title = "Blue")
        val ytCatalog = FakeSource(Service.YT_MUSIC, tracks = mapOf("MPREb_9" to yt("a", "b")))
        val resolver = FakeResolver(albums = mapOf("spotify:album:x" to ytAlbum))
        val spotify = FakeSource(Service.SPOTIFY)
        val engine = ImportEngine(library, resolver, ytCatalog, now = { 1L })

        val found = engine.importCollection(spotify, playlist("spotify:album:x", Service.SPOTIFY, OriginKind.ALBUM, "Blue"))
        val missing = engine.importCollection(spotify, playlist("spotify:album:y", Service.SPOTIFY, OriginKind.ALBUM, "Red"))
        // The same album again, now from YouTube Music itself: already there.
        val again = engine.importCollection(ytCatalog, ytAlbum)

        assertEquals("MPREb_9", library.copies.values.single().sourceId)
        assertEquals(Tally(added = 1), found.albums)
        assertEquals(Tally(unmatched = 1), missing.albums)
        assertEquals(Tally(skipped = 1), again.albums)
        assertTrue(spotify.tracksRead.isEmpty())
    }

    @Test
    fun oneListFailingDoesNotStopTheRest() = runBlocking {
        val source = FakeSource(Service.YT_MUSIC, likePages = listOf(yt("l")), tracks = mapOf("PL2" to yt("b")))

        val report = engine().import(
            source,
            ImportSelection(likes = true, collections = listOf(playlist("PL1", title = "Broken"), playlist("PL2"))),
        )

        assertEquals(listOf("l"), library.likes.map { it.videoId })
        assertEquals(listOf("PL2"), library.copies.values.map { it.sourceId })
        assertEquals(listOf(FailedItem(ImportStep.PLAYLIST, "Broken", "no such list PL1")), report.failed)
        assertFalse(report.complete)
    }

    // ---- Artists ---------------------------------------------------------------

    @Test
    fun artistsAreFollowedOnceWithSpotifyOnesUnderTheirYouTubeChannelWhenKnown() = runBlocking {
        val resolver = FakeResolver(artistIds = mapOf("sp1" to "UC1"))
        val yt = FakeSource(Service.YT_MUSIC, artistList = listOf(RemoteArtist("UC1", Service.YT_MUSIC, "One"), RemoteArtist("UC2", Service.YT_MUSIC, "Two")))
        val spotify = listOf(RemoteArtist("sp1", Service.SPOTIFY, "One"), RemoteArtist("sp3", Service.SPOTIFY, "Three"))

        val first = engine(resolver).importArtists(yt)
        val second = engine(resolver).importArtists(FakeSource(Service.SPOTIFY), spotify)

        assertEquals(Tally(added = 2), first.artists)
        assertEquals(Tally(added = 1, skipped = 1), second.artists)
        assertEquals(listOf("UC1", "UC2", "spotify:artist:sp3"), library.artists.keys.toList())
        val three = library.artists.getValue("spotify:artist:sp3")
        assertEquals("SPOTIFY", three.originService)
        assertEquals("sp3", three.originId)
        assertNull(library.artists.getValue("UC1").thumb)
    }

    @Test
    fun aLikesListCopyTurnsIntoLikesAndWhatWasUnlikedComesBackBecauseTheListenerAsked() {
        library.hidden += "x"
        val report = engine().likesFromCopy(listOf(song("a"), song("x"), song("b")))
        assertEquals(listOf("a", "x", "b"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 3), report.likes)
        assertTrue("x" !in library.hidden)
    }

    @Test
    fun aLikesListCopyCanStillHonourWhatWasUnliked() {
        library.hidden += "x"
        val report = engine().likesFromCopy(listOf(song("a"), song("x"), song("b")), honorHidden = true)
        assertEquals(listOf("a", "b"), library.likes.map { it.videoId })
        assertEquals(Tally(added = 2, skipped = 1), report.likes)
    }
}
