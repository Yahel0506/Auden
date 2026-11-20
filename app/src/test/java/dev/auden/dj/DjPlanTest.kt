package dev.auden.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjPlanTest {
    private fun t(title: String, artist: String, genre: String? = null, year: Int? = null, plays: Int = 1) =
        DjTrack(id = "$artist-$title", title = title, artist = artist, genre = genre, year = year, plays = plays)

    private val pool = listOf(
        t("Prey", "The Neighbourhood", "indie rock", 2013, 50),
        t("Zombie", "The Neighbourhood", "indie rock", 2018, 40),
        t("Sweater Weather", "The Neighbourhood", "indie rock", 2012, 30),
        t("Mosca", "Nsqk", "hip hop", 2024),
        t("Bichota", "Karol G", "reggaeton", 2020),
        t("Tusa", "Karol G", "reggaeton", 2019),
        t("Creep", "Radiohead", "alternative", 1992),
        t("Karma Police", "Radiohead", "alternative", 1997),
    )

    @Test
    fun aPlanIsReadFromTheServicesJsonEvenWhenFencedOrWithExtraFields() {
        val body = "```json\n{\"artists\":[\"The Neighbourhood\"],\"exclude_genres\":[\"reggaeton\"],\"decades\":[2010],\"mood\":\"MELANCHOLY\",\"strict\":[\"artists\"],\"extra\":1}\n```"
        val plan = DjPlan.parse(body)
        assertNotNull(plan)
        assertEquals(listOf("The Neighbourhood"), plan!!.artists)
        assertEquals(listOf("reggaeton"), plan.excludeGenres)
        assertNull(DjPlan.parse("not json at all"))
    }

    @Test
    fun namesAreHeldAgainstTheLibraryAndTheMissingOnesBecomeSearches() {
        val plan = DjPlan(artists = listOf("the neighbourhood", "SZA"), mood = "melancholy", decades = listOf(2015))
        val r = plan.toRequest("algo triste de The Neighbourhood y SZA", pool)
        assertEquals(listOf("The Neighbourhood"), r.artists)
        assertEquals(listOf("SZA"), r.searchNames)
        assertTrue(r.queries.contains("SZA"))
        assertEquals(DjMood.MELANCHOLY, r.mood)
        assertEquals(listOf(2010), r.decades)
        assertTrue(r.understood)
        assertTrue(r.needsCatalogue)
    }

    @Test
    fun aSongAskedByNameIsFoundInTheLibraryOrSearchedFor() {
        val plan = DjPlan(songs = listOf(DjPlan.PlannedSong("Prey", "The Neighbourhood"), DjPlan.PlannedSong("Flowers", "Miley Cyrus")))
        val r = plan.toRequest("pon prey y flowers", pool)
        assertEquals(listOf("The Neighbourhood-Prey"), r.songIds)
        assertEquals(listOf("Flowers Miley Cyrus"), r.songQueries)
        assertTrue(r.needsCatalogue)
    }

    @Test
    fun aTitleSpelledAletterOffStillFindsTheSong() {
        val found = DjPlan.findSong(DjPlan.PlannedSong("Sweeter Weather", null), pool)
        assertEquals("Sweater Weather", found?.title)
    }

    @Test
    fun theKeywordReadingFillsWhatThePlanLeftEmpty() {
        val ruled = DjRequestParser.parse("música de los 90", pool)
        val r = DjPlan(artists = listOf("Radiohead")).toRequest("música de los 90", pool, fallback = ruled)
        assertEquals(listOf("Radiohead"), r.artists)
        assertEquals(listOf(1990), r.decades)
    }

    @Test
    fun excludedArtistsAndGenresNeverPlayEvenWhenTheAskFallsShort() {
        val big = pool + List(12) { t("Pop $it", "Pop Artist $it", "pop", 2020) }
        val request = DjRequest(
            text = "sin reggaeton",
            decades = listOf(1960), decade = 1960,        // nothing of the sixties: must be relaxed
            excludeGenres = listOf("reggaeton"),
            excludeArtists = listOf("Nsqk"),
        )
        val planner = DjPlanner(big, 0L, 3L, request = request)
        repeat(4) {
            val block = planner.next() ?: return@repeat
            assertFalse(block.tracks.any { it.leadArtist == "Karol G" })
            assertFalse(block.tracks.any { it.leadArtist == "Nsqk" })
        }
    }

    @Test
    fun severalArtistsAreAnyOf() {
        val big = pool + List(8) { t("Radio $it", "Radiohead", "alternative", 2000) } + List(8) { t("N $it", "Nsqk", "hip hop", 2024) }
        val request = DjRequest(text = "radiohead o nsqk", artists = listOf("Radiohead", "Nsqk"))
        val planner = DjPlanner(big, 0L, 3L, request = request)
        val block = planner.next()
        assertNotNull(block)
        assertTrue(block!!.tracks.all { it.leadArtist == "Radiohead" || it.leadArtist == "Nsqk" })
    }

    @Test
    fun mergePlannedPutsNamedSongsFirstAndKeepsExcludedArtistsOut() {
        val results = listOf(
            DjTaste.Query.Named("Flowers Miley Cyrus") to listOf(DjTaste.Hit("f", "Miley Cyrus"), DjTaste.Hit("f2", "Someone Else")),
            DjTaste.Query.Plain("sad songs") to listOf(DjTaste.Hit("a", "Karol G"), DjTaste.Hit("b", "Adele"), DjTaste.Hit("c", "Adele")),
        )
        val out = DjTaste.mergePlanned(results, names = emptyList(), excluded = listOf("Karol G"), limit = 10)
        assertEquals("f", out.first())
        assertFalse("a" in out)
        assertTrue("b" in out)
    }

    @Test
    fun aGenreTheLibraryHasNoneOfIsSearchedNotDropped() {
        val r = DjPlan(genres = listOf("classical"), mood = "FOCUS").toRequest("ponme musica clasica", pool)
        assertTrue(r.catalogueOnly)
        assertTrue(r.queries.contains("classical music"))
        assertTrue(r.understood)
    }

    @Test
    fun classicalMusicIsNotARequestToRediscoverOldFavourites() {
        val r = DjRequestParser.parse("ponme musica clasica", pool)
        assertFalse(r.rediscover)
        assertTrue(DjRequestParser.parse("ponme los clasicos", pool).rediscover)
    }

    @Test
    fun theRequestSignatureIsTheHmacTheWorkerChecks() {
        assertEquals("2946e37a45bb02a0bf1bf1a7d9219543801fedd301f9f485aebd72c928639bea", DjInterpreter.sign("k", 1L, "b"))
    }

    @Test
    fun theModelsShortNameIsKeptAndATooLongOneIsNot() {
        assertEquals("música clásica", DjPlan(genres = listOf("classical"), title = " \"música clásica\". ").toRequest("x", pool).title)
        assertNull(DjPlan(genres = listOf("classical"), title = "a".repeat(60)).toRequest("x", pool).title)
    }

    @Test
    fun aSetFoundForAnAskIsCalledByItsShortName() {
        val theme = DjTheme.ByRequest("ponme algo tranquilo de los 90 sin reggaeton", null, "música tranquila de los 90")
        assertEquals("música tranquila de los 90", theme.label)
        assertEquals("ponme musica", DjTheme.ByRequest("ponme musica").label)
    }
}
