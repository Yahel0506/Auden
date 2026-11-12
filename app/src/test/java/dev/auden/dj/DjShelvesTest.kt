package dev.auden.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DjShelvesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 3_000 * day

    private fun t(
        id: String, artist: String, album: String? = null, genre: String? = null, year: Int? = null,
        plays: Int = 0, plays30: Int = 0, lastDaysAgo: Int = 0, file: Boolean = false,
    ) = DjTrack(
        id, "T$id", artist, album, genre, year, null, null, if (file) "content://$id" else null, plays, plays30,
        if (lastDaysAgo > 0) now - lastDaysAgo * day else 0L, 0L,
    )

    /**
     * A listener with history: five artists played this month, three left behind for months with whole albums,
     * a spread of genres and decades, and plenty of streamed songs to follow for discoveries.
     */
    private val library: List<DjTrack> = buildList {
        val genres = listOf("Rock", "Jazz", "Folk", "Pop", "Ambient")
        (1..5).forEach { a -> (1..8).forEach { n -> add(t("now$a-$n", "Current$a", "Disc$a", genres[a % 5], 1990 + a * 3, plays = 9 + n, plays30 = 3, lastDaysAgo = 3)) } }
        (1..5).forEach { a -> (1..8).forEach { n -> add(t("old$a-$n", "Gone$a", "Lost$a-${n % 2}", genres[(a + 2) % 5], 1980 + a, plays = 8 + n, lastDaysAgo = 200)) } }
        // Enough artists in every genre for every mood to have a set of its own.
        (1..20).forEach { a -> (1..6).forEach { n -> add(t("misc$a-$n", "Misc$a", "Misc$a-${n % 2}", genres[a % 5], 1995 + a, plays = 1 + n % 3, lastDaysAgo = 20)) } }
    }

    private fun home(tracks: List<DjTrack> = library, hour: Int = 21, variants: Map<String, Int> = emptyMap()) =
        DjShelves.build(tracks, now, hour, weekend = false, variants = variants)

    @Test
    fun aLibraryTooSmallToMakeASetOffersNothing() {
        assertTrue(home(library.take(3)).sections.isEmpty())
    }

    @Test
    fun theSectionsComeInTheOrderTheyAreShownAndNoneIsEmpty() {
        val h = home()
        assertEquals(DjSection.START, h.sections.first().first)
        val order = h.sections.map { it.first }
        assertEquals(order, order.sortedBy { it.ordinal })
        h.sections.forEach { (_, cards) -> assertTrue(cards.isNotEmpty()) }
        assertEquals(h.all.size, h.all.map { it.id }.toSet().size)
        // This listener has the history for every section the page has.
        assertEquals(DjSection.entries.toSet(), order.toSet())
    }

    @Test
    fun aListenerWithNoHistoryIsOnlyOfferedWhatTheLibraryItselfSupports() {
        val bare = library.map { it.copy(plays = 0, plays30 = 0, lastPlayedAt = 0L) }
        val sections = home(bare).sections.map { it.first }
        assertFalse(DjSection.FOR_YOU in sections)
        assertFalse(DjSection.COME_BACK in sections)
        assertFalse(DjSection.DISCOVER in sections)
        assertTrue(DjSection.EXPLORE in sections)
    }

    @Test
    fun everySetIsStartableOrIsTheDjsOwnChoice() {
        home().all.forEach { card ->
            if (card.kind != DjCardKind.FOR_YOU && card.kind != DjCardKind.SURPRISE) {
                assertNotNull("${card.id} has no request", card.request)
                assertTrue("${card.id} says nothing", card.request!!.understood)
            }
        }
    }

    @Test
    fun anArtistOrAnAlbumIsTheSubjectOfOneSetOnly() {
        val subjects = home().all
            .filter { it.kind in setOf(DjCardKind.TOP_ARTIST, DjCardKind.LOST_ARTIST, DjCardKind.THEME_ARTIST, DjCardKind.LOST_ALBUM, DjCardKind.THEME_ALBUM) }
            .map { (it.request!!.artist ?: "") + "|" + (it.request!!.album ?: "") }
        assertEquals(subjects.size, subjects.toSet().size)
    }

    @Test
    fun comingBackNamesWhatWasLeftBehindAndForHowLong() {
        val back = home().cards(DjSection.COME_BACK)
        assertTrue(back.any { it.kind == DjCardKind.LOST_SONGS && (it.number ?: 0) >= 3 })
        assertTrue(back.any { it.kind == DjCardKind.LOST_ARTIST && it.subject?.startsWith("Gone") == true })
        assertTrue(back.any { it.kind == DjCardKind.LOST_ALBUM && it.request!!.album?.startsWith("Lost") == true })
        // Nothing current is "left behind".
        assertTrue(back.none { it.subject?.startsWith("Current") == true })
    }

    @Test
    fun theMomentsAreOrderedByTheHour() {
        val moods = { hour: Int -> home(hour = hour).cards(DjSection.MOMENT).map { it.mood } }
        assertTrue("night should not lead with energy", moods(2).first() != DjMood.ENERGY)
        assertEquals(DjMood.ENERGY, moods(9).first())
    }

    @Test
    fun drawingASetAgainOffersAnotherVersionOfTheSameIdea() {
        val before = home().cards(DjSection.FOR_YOU).filter { it.kind == DjCardKind.TOP_ARTIST }.map { it.subject }
        val after = home(variants = mapOf("top-artist" to 1)).cards(DjSection.FOR_YOU).filter { it.kind == DjCardKind.TOP_ARTIST }.map { it.subject }
        assertTrue("$before vs $after", before != after)
    }

    @Test
    fun aSurpriseIsASetFromThePageNotOneStartedLately() {
        val h = home()
        val rng = Random(1)
        val first = DjShelves.surprise(h, emptySet(), rng)!!
        assertTrue(first.section != DjSection.START)
        val picked = (1..50).map { DjShelves.surprise(h, setOf(first.id), Random(it.toLong()))!!.id }
        assertFalse(first.id in picked)
        assertNull(DjShelves.surprise(DjHome(emptyList()), emptySet(), rng))
    }
}
