package dev.auden.home

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTest {
    private val day = LocalShelves.DAY_MS
    private val now = 1_000 * day

    private val text = object : LocalShelfText {
        override fun title(kind: LocalShelfKind, artist: String?) = if (artist != null) "${kind.name}:$artist" else kind.name
        override fun subtitle(kind: LocalShelfKind) = ""
        override fun songCount(count: Int) = "$count songs"
    }

    private fun played(
        id: String, artist: String = "A", total: Int = 1, p30: Int = 0, last: Long = now, art: String? = null,
    ) = PlayedTrack(id, "t$id", artist, art, total, p30, last, null)

    private fun lib(id: String, artist: String = "A", added: Long = 0, file: Boolean = true) =
        LibraryTrack(id, "t$id", artist, null, if (file) "content://$id" else null, added)

    private fun item(n: Int) = ShelfItem("i$n", "", null, "v$n", null)
    private fun remoteShelf(n: Int) = HomeShelf("R$n", listOf(item(n)))
    private fun localShelf(kind: LocalShelfKind) = LocalShelf(kind, kind.name, "", listOf(item(1)))

    private fun titles(shelves: List<HomeShelf>) = shelves.map { it.title }

    // ── Shelves ─────────────────────────────────────────────────────────────

    @Test
    fun aFreshInstallBuildsNoSongShelves() {
        assertTrue(LocalShelves.build(emptyList(), emptyList(), emptyList(), now, text).isEmpty())
    }

    @Test
    fun onRepeatTakesTheMostPlayedOfTheMonthAndNeedsFourSongs() {
        val tracks = (1..5).map { played("s$it", p30 = 10 - it, total = 10) }
        val shelf = LocalShelves.build(tracks, emptyList(), emptyList(), now, text).first { it.kind == LocalShelfKind.LOOP }
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), shelf.items.map { it.videoId })

        val few = (1..3).map { played("s$it", p30 = 5) }
        assertTrue(LocalShelves.build(few, emptyList(), emptyList(), now, text).none { it.kind == LocalShelfKind.LOOP })
    }

    @Test
    fun aSongHeardOnceThisMonthIsNotOnRepeat() {
        val tracks = (1..6).map { played("s$it", p30 = 1) }
        assertTrue(LocalShelves.build(tracks, emptyList(), emptyList(), now, text).none { it.kind == LocalShelfKind.LOOP })
    }

    @Test
    fun rediscoverIsLovedAndLongSilent() {
        val old = now - 120 * day
        val forgotten = (1..4).map { played("f$it", total = 8, last = old) }
        val recent = (1..4).map { played("r$it", total = 8, last = now - 5 * day) }
        val shelf = LocalShelves.build(forgotten + recent, emptyList(), emptyList(), now, text)
            .first { it.kind == LocalShelfKind.REDISCOVER }
        assertEquals(setOf("f1", "f2", "f3", "f4"), shelf.items.map { it.videoId }.toSet())
    }

    @Test
    fun recentlyAddedIsNewestFirstAndOnlyFiles() {
        val tracks = (1..6).map { lib("n$it", added = it.toLong()) } + lib("streamed", added = 99, file = false)
        val shelf = LocalShelves.build(emptyList(), tracks, emptyList(), now, text)
            .first { it.kind == LocalShelfKind.RECENTLY_ADDED }
        assertEquals("n6", shelf.items.first().videoId)
        assertTrue(shelf.items.none { it.videoId == "streamed" })
    }

    @Test
    fun playlistsPutPinnedFirstAndSkipEmptyOnes() {
        val shelf = LocalShelves.build(
            emptyList(), emptyList(),
            listOf(
                PlaylistSummary("a", "A", 3, null, pinned = false),
                PlaylistSummary("b", "B", 0, null, pinned = true),
                PlaylistSummary("c", "C", 2, null, pinned = true),
            ),
            now, text,
        ).first { it.kind == LocalShelfKind.PLAYLISTS }
        assertEquals(listOf("C", "A"), shelf.items.map { it.title })
        assertEquals("local:playlist:c", shelf.items.first().browseId)
    }

    @Test
    fun theArtistMixIsTheArtistHeardMostLatelyAndJoinsLibraryAndHistory() {
        val history = (1..2).map { played("h$it", artist = "Ana", p30 = 5, total = 5) } +
            played("x1", artist = "Bo", p30 = 1, total = 1)
        val library = (1..4).map { lib("l$it", artist = "Ana & Friend") }
        val shelf = LocalShelves.build(history, library, emptyList(), now, text).first { it.kind == LocalShelfKind.ARTIST_MIX }
        assertEquals("ARTIST_MIX:Ana", shelf.title)
        assertEquals(6, shelf.items.size)
    }

    @Test
    fun theSameDayGivesTheSameShuffle() {
        val library = (1..40).map { lib("l$it") }
        val a = LocalShelves.build(emptyList(), library, emptyList(), now, text).first { it.kind == LocalShelfKind.LIBRARY_SHUFFLE }
        val b = LocalShelves.build(emptyList(), library, emptyList(), now + 3_600_000, text).first { it.kind == LocalShelfKind.LIBRARY_SHUFFLE }
        val tomorrow = LocalShelves.build(emptyList(), library, emptyList(), now + day, text).first { it.kind == LocalShelfKind.LIBRARY_SHUFFLE }
        assertEquals(a.items, b.items)
        assertTrue(a.items != tomorrow.items)
    }

    @Test
    fun aLocalFileKeepsItsUriOnTheCard() {
        val tracks = (1..4).map { played("content://m/$it", p30 = 3, total = 3) }
        val shelf = LocalShelves.build(tracks, emptyList(), emptyList(), now, text).first { it.kind == LocalShelfKind.LOOP }
        assertEquals("content://m/1", shelf.items.first().localUri)
    }

    // ── Composition ─────────────────────────────────────────────────────────

    private val remote = (1..4).map(::remoteShelf)
    private val local = listOf(LocalShelfKind.LOOP, LocalShelfKind.PLAYLISTS, LocalShelfKind.RECENTLY_ADDED).map(::localShelf)

    @Test
    fun weightZeroIsTheFeedAlone() {
        val out = HomeComposer.compose(remote, local, HomeSettings(localWeight = 0), online = true)
        assertEquals(titles(remote), titles(out))
    }

    @Test
    fun weightOneHundredPutsEveryLocalShelfFirst() {
        val out = HomeComposer.compose(remote, local, HomeSettings(localWeight = 100), online = true)
        assertEquals(listOf("LOOP", "PLAYLISTS", "RECENTLY_ADDED", "R1", "R2", "R3", "R4"), titles(out))
    }

    @Test
    fun theFeedLeadsAndTheSourcesAlternateInBetween() {
        val out = titles(HomeComposer.compose(remote, local, HomeSettings(localWeight = 50), online = true))
        assertEquals("R1", out.first())
        assertEquals(7, out.size)
        // No two local shelves in a row at an even weight.
        val kinds = out.map { it.startsWith("R") }
        assertTrue(kinds.zipWithNext().none { (a, b) -> !a && !b })
    }

    @Test
    fun offlineIsOnlyTheDevice() {
        val out = HomeComposer.compose(remote, local, HomeSettings(), online = false)
        assertEquals(listOf("LOOP", "PLAYLISTS", "RECENTLY_ADDED"), titles(out))
    }

    @Test
    fun aDisabledKindAndAnEmptyShelfAreLeftOut() {
        val settings = HomeSettings(enabled = setOf(LocalShelfKind.LOOP), localWeight = 100)
        val emptyShelf = LocalShelf(LocalShelfKind.LOOP, "EMPTY", "", emptyList())
        val out = HomeComposer.compose(remote, local + emptyShelf, settings, online = true)
        assertEquals(listOf("LOOP", "R1", "R2", "R3", "R4"), titles(out))
    }

    @Test
    fun nothingLocalLeavesTheFeedUntouchedAndNothingRemoteLeavesTheDevice() {
        assertEquals(titles(remote), titles(HomeComposer.compose(remote, emptyList(), HomeSettings(), online = true)))
        assertEquals(titles(local.map { HomeShelf(it.title, it.items) }), titles(HomeComposer.compose(emptyList(), local, HomeSettings(), online = true)))
    }

    @Test
    fun aHighWeightLeadsWithTheListenersOwn() {
        val out = titles(HomeComposer.compose(remote, local, HomeSettings(localWeight = 80), online = true))
        assertEquals("LOOP", out.first())
    }
}
