package dev.auden.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjRequestParserMoreTest {
    private fun t(artist: String, album: String? = null, year: Int? = null) =
        DjTrack(id = "$artist-${album}", title = "x", artist = artist, album = album, year = year)

    private val pool = listOf(t("NSQK"), t("Imagine Dragons"), t("Kendrick Lamar"), t("Radiohead"), t("Los Prisioneros"))
    private val aliases = mapOf("nescuic" to "NSQK")

    @Test
    fun severalDecadesAtOnce() {
        assertEquals(listOf(1980, 1990), DjRequestParser.parse("música de los 80 y 90", pool).allDecades)
        assertEquals(listOf(1980, 1990), DjRequestParser.parse("80s and 90s music", pool).allDecades)
        assertEquals(listOf(1980, 1990), DjRequestParser.parse("de los ochentas y noventas", pool).allDecades)
    }

    @Test
    fun aRangeOfDecadesTakesTheOnesBetween() {
        assertEquals(listOf(1970, 1980, 1990), DjRequestParser.parse("de los 70 a los 90", pool).allDecades)
        assertEquals(listOf(1990, 2000), DjRequestParser.parse("entre 1995 y 2005", pool).allDecades)
        assertEquals(listOf(1970, 1980, 1990, 2000), DjRequestParser.parse("from the 70s to the 2000s", pool).allDecades)
    }

    @Test
    fun aSingleDecadeStillWorksAndABareNumberIsNotOne() {
        assertEquals(listOf(1990), DjRequestParser.parse("rock de los 90", pool).allDecades)
        assertEquals(listOf(1980), DjRequestParser.parse("the eighties", pool).allDecades)
        assertTrue(DjRequestParser.parse("ponme 10 canciones", pool).allDecades.isEmpty())
    }

    @Test
    fun anArtistSpelledOrSaidApproximatelyIsStillFound() {
        assertEquals("Imagine Dragons", DjRequestParser.parse("pon imagin dragons", pool).artist)
        assertEquals("Kendrick Lamar", DjRequestParser.parse("algo de kendrik lamar", pool).artist)
        assertEquals("Radiohead", DjRequestParser.parse("radio head", pool).artist)
    }

    @Test
    fun anArtistSaidTheWayTheVoiceSaysItIsFound() {
        assertEquals("NSQK", DjRequestParser.parse("ponme nescuic", pool, aliases).artist)
        assertNull(DjRequestParser.parse("ponme nescuic", pool).artist)
    }

    @Test
    fun likeAnArtistIsADiscoveryAroundThem() {
        val r = DjRequestParser.parse("algo como radiohead", pool)
        assertTrue(r.discover)
        assertEquals("Radiohead", r.discoverSeed)
        assertNull(r.artist)
    }

    @Test
    fun anArtistNotInTheLibraryIsNotInvented() {
        assertNull(DjRequestParser.parse("pon a bad bunny", pool).artist)
        assertNull(DjRequestParser.parse("algo tranquilo", pool).artist)
    }
}

class DjArtistAskTest {
    @Test
    fun anAskThatIsOnlyANameYieldsTheName() {
        assertEquals("SZA", DjRequestParser.artistNameIn("SZA"))
        assertEquals("SZA", DjRequestParser.artistNameIn("pon a SZA"))
        assertEquals("Taylor Swift", DjRequestParser.artistNameIn("música de Taylor Swift"))
        assertEquals("Kendrick Lamar", DjRequestParser.artistNameIn("play some Kendrick Lamar"))
    }

    @Test
    fun anAskThatSaysMoreIsNotJustAName() {
        assertNull(DjRequestParser.artistNameIn("algo para correr sin letra"))
        assertNull(DjRequestParser.artistNameIn("pon una playlist de cosas muy raras que me gustan mucho"))
        assertNull(DjRequestParser.artistNameIn(""))
    }

    @Test
    fun aSetForAnArtistIsTheirSongsFirstAndOnlyFilledWithOthers() {
        val results = listOf<Pair<DjTaste.Query, List<DjTaste.Hit>>>(
            DjTaste.Query.Plain("SZA") to listOf(
                DjTaste.Hit("1", "NSQK"), DjTaste.Hit("2", "SZA"), DjTaste.Hit("3", "SZA"), DjTaste.Hit("4", "Cover Band"),
                DjTaste.Hit("5", "SZA, Travis Scott"), DjTaste.Hit("6", "SZA"),
            ),
        )
        val ids = DjTaste.mergeForArtist(results, "SZA", 5)
        assertEquals(listOf("2", "3", "5", "6", "1"), ids)
    }
}

class DjSeveralArtistsTest {
    @Test
    fun severalNamesAreSeveralNames() {
        assertEquals(listOf("SZA", "Taylor Swift"), DjRequestParser.artistNamesIn("SZA y Taylor Swift"))
        assertEquals(listOf("A\$AP ROCKY"), DjRequestParser.artistNamesIn("A\$AP ROCKY"))
        assertEquals(listOf("SZA", "Kendrick Lamar"), DjRequestParser.artistNamesIn("pon SZA and Kendrick Lamar"))
        assertNull(DjRequestParser.artistNameIn("SZA y Taylor Swift"))
    }

    @Test
    fun aSetForTwoArtistsTakesTheirSongsInTurn() {
        val results = listOf<Pair<DjTaste.Query, List<DjTaste.Hit>>>(
            DjTaste.Query.Plain("SZA") to listOf(DjTaste.Hit("s1", "SZA"), DjTaste.Hit("s2", "SZA"), DjTaste.Hit("s3", "SZA")),
            DjTaste.Query.Plain("Taylor Swift") to listOf(DjTaste.Hit("t1", "Taylor Swift"), DjTaste.Hit("t2", "Taylor Swift")),
            DjTaste.Query.Plain("SZA Taylor Swift") to listOf(DjTaste.Hit("x1", "Somebody Else")),
        )
        assertEquals(listOf("s1", "t1", "s2", "t2", "s3", "x1"), DjTaste.mergeForArtists(results, listOf("SZA", "Taylor Swift"), 8))
    }
}
