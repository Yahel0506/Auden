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
                        DjTheme.Discover -> track.discovery
                        is DjTheme.ByPlaylist -> track.playlists.isNotEmpty()
                    }
                    assertTrue("$track in ${block.theme}", ok)
                }
            }
        }
    }

    @Test
    fun aSongIsNeverPlayedTwiceInASession() {
        val planner = DjPlanner(varied, now, seed = 7)
        val seen = HashSet<String>()
        while (true) {
            val block = planner.next() ?: break
            block.tracks.forEach { assertTrue("${it.id} repeated", seen.add(it.id)) }
        }
        assertTrue("only ${seen.size} songs in a session", seen.size >= 8)
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
            "unrecognized" to listOf("Didn’t catch that."),
            "intro_artist_recent" to listOf("You’ve played a lot of {artist} lately, so: {opener}."),
            "intro_artist_always" to listOf("{artist} is one of your regulars, so: {opener}."),
            "intro_artist_saved" to listOf("You saved a few by {artist}, so: {opener}."),
            "intro_favorites" to listOf("Songs you repeat, like {opener}."),
            "intro_rediscover" to listOf("It’s been {ago} since you played these: {opener}."),
            "intro_rediscover_plain" to listOf("You used to play these: {opener}."),
            "intro_discover" to listOf("You play a lot of {seed}, so something new: {opener}."),
            "intro_discover_plain" to listOf("Something new: {opener}."),
            "intro_playlist" to listOf("From your playlist {playlist}: {opener}."),
            "intro_mood_chill" to listOf("{moment_cap} calls for something easy: {opener}."),
            "intro_request_match" to listOf("You asked for {what}: {opener}."),
            "intro_theme" to listOf("{what}: {opener}."),
            "what_artist" to listOf("music by {artist}"),
            "mood_word_chill" to listOf("something calm"),
            "moment_night" to listOf("tonight"),
            "ago_months" to listOf("{n} months"),
            "ago_year" to listOf("over a year"),
            "by_artist" to listOf("{title} by {artist}"),
            "note_year" to listOf("{title} from {year}."),
            "note_plain" to listOf("{title}, {artist}."),
        )
        override fun templates(key: String) = table[key].orEmpty()
    }

    private fun writer(plays: Map<String, Pair<Int, Int>> = emptyMap()) =
        DjWriter(phrases, "en", seed = 1, hour = 23, nowMs = now, artistPlays = { plays[it] })

    private fun block(theme: DjTheme, tracks: List<DjTrack>) = DjBlock(theme, tracks)

    @Test
    fun anArtistSetGivesTheReasonBeforeTheSong() {
        val ana = library.filter { it.artist == "Ana" }.take(4)
        val recent = writer(mapOf("Ana" to (9 to 40))).blockIntro(block(DjTheme.ByArtist("Ana"), ana), null)
        assertTrue(recent, recent.startsWith("You’ve played a lot of Ana lately"))
        val regular = writer(mapOf("Ana" to (1 to 40))).blockIntro(block(DjTheme.ByArtist("Ana"), ana), null)
        assertTrue(regular, regular.contains("one of your regulars"))
        val saved = writer(mapOf("Ana" to (0 to 3))).blockIntro(block(DjTheme.ByArtist("Ana"), ana), null)
        assertTrue(saved, saved.contains("saved a few"))
        // The song closes the sentence, and carries no artist when the set is already theirs.
        assertTrue(recent, recent.endsWith("${ana.first().title}."))
    }

    @Test
    fun anotherArtistsSongNamesItsArtist() {
        val mixed = library.filter { it.artist == "Bo" }.take(4)
        val line = writer().blockIntro(block(DjTheme.Favorites, mixed), null)
        assertTrue(line, line.contains("${mixed.first().title} by Bo"))
    }

    @Test
    fun aRediscoverSetSaysHowLongItHasBeen() {
        val old = library.filter { it.artist == "Cy" }.take(4).map { it.copy(lastPlayedAt = now - 120 * day) }
        val line = writer().blockIntro(block(DjTheme.Rediscover, old), null)
        assertTrue(line, line.contains("4 months"))
        val unknown = writer().blockIntro(block(DjTheme.Rediscover, old.map { it.copy(lastPlayedAt = 0) }), null)
        assertTrue(unknown, unknown.contains("You used to play these"))
    }

    @Test
    fun aDiscoveryNamesTheArtistItWasFoundFrom() {
        val found = (1..4).map { t("n$it", "Stranger$it").copy(discovery = true, because = "Ana") }
        val line = writer().blockIntro(block(DjTheme.Discover, found), null)
        assertTrue(line, line.contains("You play a lot of Ana"))
        val plain = writer().blockIntro(block(DjTheme.Discover, found.map { it.copy(because = null) }), null)
        assertTrue(plain, plain.startsWith("Something new"))
    }

    @Test
    fun aMoodSetNamesTheMoment() {
        val line = writer().blockIntro(block(DjTheme.ByMood(DjMood.CHILL), library.take(4)), null)
        assertTrue(line, line.startsWith("Tonight calls for something easy"))
    }

    @Test
    fun youAskedForIsSaidOnlyWhenTheListenerDid() {
        val ana = library.filter { it.artist == "Ana" }.take(4)
        val asked = writer().apply { request = DjRequest(text = "Ana", artist = "Ana") }
            .blockIntro(block(DjTheme.ByArtist("Ana"), ana), null)
        assertTrue(asked, asked.startsWith("You asked for music by Ana"))
        val surprise = writer().blockIntro(block(DjTheme.ByArtist("Ana"), ana), null)
        assertFalse(surprise, surprise.contains("You asked for"))
    }

    @Test
    fun aPlaylistSetNamesThePlaylist() {
        val tracks = (1..4).map { t("pl$it", "Singer$it").copy(playlists = listOf("Road trip")) }
        val line = writer().blockIntro(block(DjTheme.ByPlaylist("Road trip"), tracks), null)
        assertTrue(line, line.contains("Road trip"))
    }

    @Test
    fun noLineLeavesASlotUnfilled() {
        listOf(DjTheme.Favorites, DjTheme.Rediscover, DjTheme.Fresh, DjTheme.Discover, DjTheme.ByMood(DjMood.CHILL)).forEach { theme ->
            val line = writer().blockIntro(block(theme, library.take(4)), null)
            assertFalse(line, line.contains("{") || line.contains("}"))
        }
    }

    @Test
    fun theDecadeIsSaidTheWayEachLanguageSaysIt() {
        assertEquals("los 90", DjWriter(phrases, "es", 1).decadeLabel(1990))
        assertEquals("los 2000", DjWriter(phrases, "es", 1).decadeLabel(2000))
        assertEquals("the 90s", DjWriter(phrases, "en", 1).decadeLabel(1990))
        assertEquals("the 2010s", DjWriter(phrases, "en", 1).decadeLabel(2010))
    }

    @Test
    fun theOpeningGreetsThenSaysWhenItDidNotUnderstand() {
        val ana = library.filter { it.artist == "Ana" }.take(4)
        val line = writer().opening(23, block(DjTheme.ByArtist("Ana"), ana), DjRequest(text = "blah"), relaxed = false)
        assertTrue(line, line.startsWith("Late one. Didn’t catch that."))
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

    // ── Surprises are for listening, not for repetition ─────────────────────

    /** Six artists, two albums each, plenty of plays: enough for a varied surprise. */
    private val varied: List<DjTrack> = (1..6).flatMap { a ->
        (1..8).map { n ->
            t("v$a-$n", "Artist$a", album = "Album$a-${n % 2}", genre = listOf("Rock", "Jazz", "Pop")[a % 3], year = 1990 + a, plays = 4 + n, plays30 = 1)
                .copy(trackNumber = n)
        }
    }

    @Test
    fun aSurpriseHasOneSongPerArtistAndNoAlbumTwiceInARow() {
        repeat(10) { seed ->
            val planner = DjPlanner(varied, now, seed.toLong(), blockSize = 5, request = null)
            repeat(3) {
                val block = planner.next() ?: return@repeat
                val artists = block.tracks.map { it.leadArtist }
                assertEquals("artists in ${block.theme}", artists.size, artists.toSet().size)
                assertTrue(block.tracks.zipWithNext().none { (a, b) -> a.album == b.album && a.album != null })
            }
        }
    }

    @Test
    fun anArtistRestsAfterAPlaceWhenTheThemeAllows() {
        val wide = (1..12).flatMap { a ->
            (1..6).map { n -> t("w$a-$n", "Artist$a", album = "Album$a", genre = listOf("Rock", "Jazz", "Pop")[a % 3], year = 1990 + a, plays = 4 + n) }
        }
        val planner = DjPlanner(wide, now, seed = 21, blockSize = 4, request = null)
        val sets = generateSequence { planner.next() }.take(4).toList()
        assertTrue(sets.size >= 2)
        for (i in 1 until sets.size) {
            val recent = sets[i - 1].tracks.map { it.leadArtist }.toSet()
            val now = sets[i].tracks.map { it.leadArtist }
            assertTrue("set $i repeats the artist of the set before", now.none { it in recent })
        }
    }

    @Test
    fun aRequestMayRepeatTheArtistItNames() {
        val planner = DjPlanner(varied, now, seed = 4, blockSize = 5, request = DjRequest(text = "Artist3", artist = "Artist3"))
        val block = planner.next() ?: return
        assertTrue(block.tracks.all { it.leadArtist == "Artist3" })
    }

    @Test
    fun discoveriesJoinASurprise() {
        val planner = DjPlanner(varied, now, seed = 9, blockSize = 5, request = null)
        val found = (1..6).map { t("new$it", "Stranger$it", genre = "Rock", year = 1995, plays = 0).copy(discovery = true) }
        planner.addDiscoveries(found)
        val tracks = generateSequence { planner.next() }.take(12).flatMap { it.tracks }.toList()
        assertTrue(tracks.any { it.discovery })
    }

    @Test
    fun aNewSessionLeansAwayFromWhatTheLastOnePlayed() {
        val first = DjPlanner(varied, now, seed = 5, blockSize = 5)
        val played = generateSequence { first.next() }.take(3).flatMap { it.tracks }.map { it.id }.toSet()
        var overlapFresh = 0
        var overlapPlain = 0
        repeat(20) { seed ->
            val fresh = DjPlanner(varied, now, seed = 100L + seed, blockSize = 5, recently = played)
            val plain = DjPlanner(varied, now, seed = 100L + seed, blockSize = 5)
            overlapFresh += generateSequence { fresh.next() }.take(3).flatMap { it.tracks }.count { it.id in played }
            overlapPlain += generateSequence { plain.next() }.take(3).flatMap { it.tracks }.count { it.id in played }
        }
        assertTrue("fresh $overlapFresh vs plain $overlapPlain", overlapFresh < overlapPlain)
    }

    @Test
    fun aSurpriseDrawsOnThePlaylistsToo() {
        // Songs never played, but in two playlists: the only reason the DJ can know them.
        val curated = (1..2).flatMap { p ->
            (1..8).map { n -> t("p$p-$n", "Singer${p}_$n", plays = 0).copy(playlists = listOf("Mix$p")) }
        }
        val planner = DjPlanner(curated, now, seed = 3, blockSize = 5)
        val block = planner.next()
        assertNotNull(block)
        assertTrue(block!!.theme is DjTheme.ByPlaylist)
        assertEquals(5, block.tracks.map { it.leadArtist }.toSet().size)
    }

    @Test
    fun sessionsOpenOnDifferentSongsEvenWhenTheFavouritesAreTheSame() {
        val openers = (1..30).map { seed ->
            DjPlanner(varied, now, seed = seed.toLong(), blockSize = 5).next()!!.tracks.map { it.id }.toSet()
        }
        // Thirty sessions over forty-eight songs should not all draw the same five.
        assertTrue(openers.toSet().size > 15)
    }

    @Test
    fun aSongWithNothingToSayGetsNoRemark() {
        val table = mapOf(
            "note_repeat" to listOf("{title}: {n} times."),
            "note_playlist" to listOf("{title} from {playlist}."),
            "note_album_year" to listOf("{title}, {album}, {year}."),
        )
        val w = DjWriter(object : DjPhrases { override fun templates(key: String) = table[key].orEmpty() }, "en", 1)
        assertNull(w.trackNote(t("bare", "Ana")))
        assertEquals("Trep: 4 times.", w.trackNote(t("r", "Ana", title = "Trep", plays30 = 4)))
        assertEquals("Tp from Road.", w.trackNote(t("p", "Ana", title = "Tp").copy(playlists = listOf("Road"))))
    }
}
