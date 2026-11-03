package dev.auden.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjTasteTest {

    private fun track(id: String, artist: String, plays: Int, genre: String? = null, plays30: Int = 0) =
        DjTrack(id = id, title = id, artist = artist, genre = genre, plays = plays, plays30 = plays30)

    @Test
    fun theProfileIsTheArtistsPlayedMostAndTheirGenres() {
        val tracks = listOf(
            track("1", "Nsqk", plays = 50, genre = "Hip Hop"),
            track("2", "Héroes del Silencio", plays = 30),
            track("3", "Quiet Artist", plays = 1),
            track("4", "Found", plays = 99).copy(discovery = true),
        )
        val profile = DjTaste.profile(tracks, genresOf = { if (it == "Héroes del Silencio") listOf("Rock") else emptyList() })
        assertEquals(listOf("Nsqk", "Héroes del Silencio", "Quiet Artist"), profile.artists)
        // Both genres, the heavier first; a discovery is not the listener's taste.
        assertEquals(listOf("hip hop", "rock"), profile.genres)
    }

    @Test
    fun thisMonthCountsDouble() {
        val tracks = listOf(track("1", "Old", plays = 10), track("2", "Recent", plays = 2, plays30 = 5))
        assertEquals("Recent", DjTaste.profile(tracks, { emptyList() }).artists.first())
    }

    @Test
    fun anAskThatNamesAGenreIsSearchedAsItIs() {
        val profile = DjTaste.Profile(listOf("A", "B"), listOf("rock", "hip hop"))
        assertEquals(listOf<DjTaste.Query>(DjTaste.Query.Plain("rock en español")), DjTaste.queries("rock en español", profile, explicitGenre = true))
    }

    @Test
    fun otherwiseItIsSearchedInEachGenreAndFromEachArtist() {
        val profile = DjTaste.Profile(listOf("A", "B"), listOf("rock", "hip hop"))
        val queries = DjTaste.queries("música triste", profile, explicitGenre = false)
        assertEquals(
            listOf("música triste rock", "música triste hip hop", "música triste A", "música triste B", "música triste"),
            queries.map { it.text },
        )
    }

    @Test
    fun withNothingKnownAboutTheListenerItIsSearchedAsItIs() {
        assertEquals(1, DjTaste.queries("algo triste", DjTaste.Profile(emptyList(), emptyList()), false).size)
    }

    @Test
    fun theGenresTakeTurnsAndTheListenersOwnArtistsComeFirst() {
        val rock = DjTaste.Query.InGenre("triste rock", "rock")
        val hiphop = DjTaste.Query.InGenre("triste hip hop", "hip hop")
        val results = listOf(
            rock to listOf(DjTaste.Hit("r1", "Stranger"), DjTaste.Hit("r2", "Mine Rock")),
            hiphop to listOf(DjTaste.Hit("h1", "Stranger Two"), DjTaste.Hit("h2", "Mine Rap")),
        )
        val ids = DjTaste.merge(results, knownArtists = setOf("mine rock", "mine rap"), limit = 4)
        // One of each in turn; within each genre the listener's own artist before the stranger.
        assertEquals(listOf("r2", "h2", "r1", "h1"), ids)
    }

    @Test
    fun anArtistOfTheirsWhoFitsJoinsWhateverTheGenre() {
        val rock = DjTaste.Query.InGenre("triste rock", "rock")
        val artist = DjTaste.Query.FromArtist("triste Nsqk", "Nsqk")
        val results = listOf(
            rock to listOf(DjTaste.Hit("r1", "Someone")),
            // Only Nsqk's own songs count, whatever else the search returned.
            artist to listOf(DjTaste.Hit("n1", "Nsqk"), DjTaste.Hit("x", "Unrelated"), DjTaste.Hit("n2", "Nsqk")),
        )
        val ids = DjTaste.merge(results, knownArtists = setOf("nsqk"), limit = 10)
        assertTrue("n1" in ids && "n2" in ids)
        assertFalse("x" in ids)
    }

    @Test
    fun theAskOnItsOwnOnlyFillsWhatIsLeftAndNoArtistCrowdsTheSet() {
        val rock = DjTaste.Query.InGenre("triste rock", "rock")
        val plain = DjTaste.Query.Plain("triste")
        val results = listOf(
            rock to List(5) { DjTaste.Hit("r$it", "Same Band") },
            plain to listOf(DjTaste.Hit("p1", "Other"), DjTaste.Hit("p2", "Another")),
        )
        val ids = DjTaste.merge(results, emptySet(), limit = 10)
        assertEquals(2, ids.count { it.startsWith("r") })
        assertEquals(listOf("p1", "p2"), ids.filter { it.startsWith("p") })
    }

    @Test
    fun genresAreRecognisedInTheAskAndMoodsAreNot() {
        assertTrue(DjRequestParser.namesGenre("rock en español"))
        assertTrue(DjRequestParser.namesGenre("algo de hip hop"))
        assertTrue(DjRequestParser.namesGenre("Reggaetón para bailar"))
        assertFalse(DjRequestParser.namesGenre("música triste"))
        assertFalse(DjRequestParser.namesGenre("algo para concentrarme"))
        // "pop" inside another word is not the genre.
        assertFalse(DjRequestParser.namesGenre("canciones populares"))
    }
}
