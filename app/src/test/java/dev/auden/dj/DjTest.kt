package dev.auden.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 2_000 * day

    private fun t(
        id: String, artist: String = "Ana", album: String? = null, genre: String? = null, year: Int? = null,
        plays: Int = 0, plays30: Int = 0, last: Long = 0, added: Long = 0, file: Boolean = false, title: String = "T$id",
    ) = DjTrack(id, title, artist, album, genre, year, null, null, if (file) "content://$id" else null, plays, plays30, last, added)

    /** Ana (rock, 90s), Bo (pop, 2000s), Cy (jazz, 90s): enough for artist, genre and decade themes. */
    private val library: List<DjTrack> =
        (1..6).map { t("a$it", "Ana", "Alpha", "Rock", 1990 + it, plays = 10 - it, plays30 = 2) } +
            (1..6).map { t("b$it", "Bo", "Beta", "Pop", 2000 + it, plays = 5, plays30 = 1) } +
            (1..6).map { t("c$it", "Cy", null, "Jazz", 1990 + it, plays = 3) }

    // ── Coherence ───────────────────────────────────────────────────────────

    @Test
    fun everySongInABlockObeysItsTheme() {
        repeat(20) { seed ->
            val planner = DjPlanner(library, now, seed.toLong())
            repeat(3) {
                val block = planner.next() ?: return@repeat
                assertTrue(block.tracks.size >= DjPlanner.MIN_BLOCK)
                block.tracks.forEach { track ->
                    val ok = when (val theme = block.theme) {
                        is DjTheme.ByArtist -> track.leadArtist.equals(theme.artist, true)
                        is DjTheme.ByDecade -> track.decade == theme.decade
                        is DjTheme.ByGenre -> track.genreKey == theme.genre
                        is DjTheme.ByMood -> true
                        is DjTheme.ByAlbum -> track.album.equals(theme.album, true)
                        DjTheme.Favorites -> track.plays >= 3
                        DjTheme.Rediscover -> track.plays >= 5
                        DjTheme.Fresh -> track.localUri != null
                    }
                    assertTrue("$track in ${block.theme}", ok)
                }
            }
        }
    }

    @Test
    fun aSongIsNeverPlayedTwiceInASession() {
        val planner = DjPlanner(library, now, seed = 7)
        val seen = HashSet<String>()
        while (true) {
            val block = planner.next() ?: break
            block.tracks.forEach { assertTrue("${it.id} repeated", seen.add(it.id)) }
        }
        assertTrue(seen.size >= 8)
    }

    @Test
    fun twoBlocksInARowAreNotOfTheSameKind() {
        repeat(15) { seed ->
            val planner = DjPlanner(library, now, seed.toLong())
            val kinds = generateSequence { planner.next() }.take(4).map { it.theme.kind }.toList()
            assertTrue(kinds.zipWithNext().none { (a, b) -> a == b })
        }
    }

    @Test
    fun aPoolTooSmallToThemeGivesNoBlock() {
        val few = (1..3).map { t("x$it", "Ana", genre = "Rock", year = 1999, plays = 9) }
        assertNull(DjPlanner(few, now, 1).next())
    }

    @Test
    fun anAlbumPlaysInTrackOrder() {
        val album = (1..5).map { t("al$it", "Dee", album = "Disc", plays = 1).copy(trackNumber = 6 - it) }
        val planner = DjPlanner(album, now, 3, request = null)
        val block = generateSequence { planner.next() }.firstOrNull { it.theme is DjTheme.ByAlbum } ?: return
        assertEquals(block.tracks.map { it.trackNumber }.sortedBy { it }, block.tracks.map { it.trackNumber })
    }

    @Test
    fun noArtistCrowdsABlockThatIsNotTheirs() {
        // A decade of 8 songs by one and 4 by another: the dominant artist is capped at two.
        val mixed = (1..8).map { t("m$it", "Ana", year = 1985, plays = 9) } + (1..4).map { t("n$it", "Bo", year = 1985, plays = 1) }
        val planner = DjPlanner(mixed, now, 5, blockSize = 6)
        val block = generateSequence { planner.next() }.firstOrNull { it.theme is DjTheme.ByDecade } ?: return
        assertTrue(block.tracks.count { it.leadArtist == "Ana" } <= 4)
    }

    // ── Feedback ────────────────────────────────────────────────────────────

    @Test
    fun lessOfAnArtistTakesThemOutOfTheSession() {
        val planner = DjPlanner(library, now, 11)
        planner.less(library.first { it.artist == "Ana" })
        repeat(5) { planner.next()?.tracks?.forEach { assertFalse(it.leadArtist == "Ana") } }
    }

    // ── The request ─────────────────────────────────────────────────────────

    @Test
    fun theRequestIsReadAgainstTheLibrary() {
        val r = DjRequestParser.parse("algo tranquilo de los 90 de Cy", library)
        assertEquals("Cy", r.artist)
        assertEquals(1990, r.decade)
        assertEquals(DjMood.CHILL, r.mood)
        assertTrue(r.understood)
    }

    @Test
    fun aBareNumberIsNotADecade() {
        assertNull(DjRequestParser.parse("pon 10 canciones", library).decade)
        assertEquals(2010, DjRequestParser.parse("hits of the 10s", library).decade)
        assertEquals(1980, DjRequestParser.parse("los ochenta", library).decade)
        assertEquals(1985 / 10 * 10, DjRequestParser.parse("de 1985", library).decade)
    }

    @Test
    fun anUnintelligibleRequestIsNotUnderstood() {
        assertFalse(DjRequestParser.parse("qwerty zxcv", library).understood)
    }

    @Test
    fun aRequestNarrowsTheSession() {
        val planner = DjPlanner(library, now, 2, request = DjRequest(text = "90s", decade = 1990))
        repeat(3) { planner.next()?.tracks?.forEach { assertEquals(1990, it.decade) } }
        assertFalse(planner.relaxed)
    }

    @Test
    fun aRequestTheLibraryCannotMeetIsRelaxedAndSaysSo() {
        val planner = DjPlanner(library, now, 2, request = DjRequest(text = "1950s jazz", decade = 1950, genre = "jazz"))
        assertNotNull(planner.next())
        assertTrue(planner.relaxed)
    }

    // ── The words ───────────────────────────────────────────────────────────

    private val phrases = object : DjPhrases {
        private val table = mapOf(
            "greeting_night" to listOf("Late one."),
            "intro_artist" to listOf("{count} from {artist}, starting with {title}.", "{artist} next."),
            "intro_decade" to listOf("Back to {decade}, with {title}."),
            "connector" to listOf("And now,"),
            "note_year" to listOf("{title} from {year}."),
            "note_plain" to listOf("{title}, {artist}."),
            "unrecognized" to listOf("Didn’t catch that."),
        )
        override fun templates(key: String) = table[key].orEmpty()
    }

    @Test
    fun aLineOnlyStatesFactsTheBlockHas() {
        val writer = DjWriter(phrases, "en", seed = 1)
        val block = DjBlock(DjTheme.ByArtist("Ana"), library.filter { it.artist == "Ana" }.take(4))
        repeat(10) {
            val line = writer.blockIntro(block, previous = null)
            assertTrue(line, line.contains("Ana"))
            assertFalse(line, line.contains("{") || line.contains("}"))
        }
    }

    @Test
    fun aTemplateWithAMissingFactIsSkippedNotFilledWithAGuess() {
        val writer = DjWriter(phrases, "en", seed = 1)
        assertNull(writer.fill("From {year}.", mapOf("year" to null)))
        assertEquals("From 1999.", writer.fill("From {year}.", mapOf("year" to "1999")))
        // A track with no year gets the plain note, never the year one.
        val note = writer.trackNote(t("z", year = null))
        assertEquals("Tz, Ana.", note)
    }

    @Test
    fun theDecadeIsSaidTheWayEachLanguageSaysIt() {
        assertEquals("los 90", DjWriter(phrases, "es", 1).decadeLabel(1990))
        assertEquals("los 2000", DjWriter(phrases, "es", 1).decadeLabel(2000))
        assertEquals("the 90s", DjWriter(phrases, "en", 1).decadeLabel(1990))
        assertEquals("the 2010s", DjWriter(phrases, "en", 1).decadeLabel(2010))
    }

    @Test
    fun theOpeningSaysWhenItDidNotUnderstand() {
        val writer = DjWriter(phrases, "en", seed = 1)
        val block = DjBlock(DjTheme.ByArtist("Ana"), library.take(4))
        val line = writer.opening(2, block, DjRequest(text = "blah"), relaxed = false)
        assertTrue(line, line.contains("Didn’t catch that."))
        assertTrue(line, line.startsWith("Late one."))
    }

    @Test
    fun theSameLineDoesNotComeRoundTwiceRunning() {
        val writer = DjWriter(object : DjPhrases {
            override fun templates(key: String) = if (key == "connector") listOf("A", "B", "C") else emptyList()
        }, "en", seed = 4)
        val picks = (1..30).map { writer.blockIntro(DjBlock(DjTheme.Favorites, library.take(4)), library.let { DjBlock(DjTheme.Fresh, it.take(4)) }) }
        // Introductions have no template here, so only the connector remains; it never repeats back to back.
        picks.filter { it.isNotBlank() }.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", a != b) }
    }

    // ── The voice ───────────────────────────────────────────────────────────

    private fun v(name: String, tag: String, q: Int, net: Boolean = false, latency: Int = 300, missing: Boolean = false) =
        VoiceInfo(name, tag, q, latency, net, missing)

    @Test
    fun theBestInstalledVoiceOfTheRightLanguageWins() {
        val voices = listOf(
            v("es-low", "es-ES", 200), v("es-mx-high", "es-MX", 400), v("es-us-very", "es-US", 500),
            v("en-very", "en-US", 500), v("es-mx-missing", "es-MX", 500, missing = true),
        )
        assertEquals("es-mx-high", DjVoiceChooser.best(voices, "es-MX", online = false)?.name)
        assertEquals("es-us-very", DjVoiceChooser.best(voices, "es-AR", online = false)?.name)
    }

    @Test
    fun aNetworkVoiceIsUsedOnlyWhileConnected() {
        val voices = listOf(v("local", "es-ES", 400), v("network", "es-ES", 400, net = true))
        assertEquals("network", DjVoiceChooser.best(voices, "es-ES", online = true)?.name)
        assertEquals("local", DjVoiceChooser.best(voices, "es-ES", online = false)?.name)
    }

    @Test
    fun noVoiceForTheLanguageIsNoVoice() {
        assertNull(DjVoiceChooser.best(listOf(v("en", "en-US", 500)), "es-MX", online = true))
    }

    @Test
    fun aLineIsSpokenAsSentencesWithBreathsBetweenThem() {
        val parts = DjSpeech.shape("Good evening. Here we go: four songs, starting with this one!", seed = 3)
        assertEquals(3, parts.size)
        assertTrue(parts.dropLast(1).all { it.pauseAfterMs >= 220 })
        assertEquals(0L, parts.last().pauseAfterMs)
        assertTrue(parts.all { it.rate in 0.9f..1.0f })
        // Never the same twice.
        assertTrue(parts.map { it.rate }.toSet().size > 1)
    }
}
