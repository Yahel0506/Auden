package com.music.bitchord.data.innertube

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.YouTubeMusicLyrics
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.WatchTarget
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.Headers
import io.ktor.http.encodedPath
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which reads are made as the account and which as nobody.
 *
 * The principle is that catalogue reads (Home's shelves, the DJ's and AutoPlay's radio, artist cards, search,
 * lyrics, a track's links) never carry the account, and that the cookie is only for the account's own library,
 * the writes the listener asks for, and playback. That is a property of the requests, so the requests are what is
 * read: every one this client builds is stopped by an interceptor before it reaches the network, its headers and
 * body are kept, and the test looks at them. Nothing here touches the network.
 *
 * A signed-in process is set up the hard way — a cookie with a SAPISID, a scope adopted from a signed-in page with
 * its own visitor id, and a channel chosen on top — because each of those used to leak into an "anonymous"
 * request by a different route.
 */
class AccountScopeTest {

    private class Sent(val path: String, val headers: Headers, val body: String)

    private class Stopped : RuntimeException("stopped by AccountScopeTest before reaching the network")

    companion object {
        private const val COOKIE = "SAPISID=test-sapisid; HSID=test-hsid; SID=test-sid"
        private const val SESSION_VISITOR = "CgtTRVNTSU9OVklTSVRPUl9CT1VORF9UT19USEVfQUNDT1VOVA%3D%3D"
        private const val SHELL_DSID = "shell-data-sync-id"
        private const val CHOSEN_DSID = "chosen-data-sync-id"

        private val sent = CopyOnWriteArrayList<Sent>()

        init {
            Innertube.client.plugin(HttpSend).intercept { request ->
                val body = (request.body as? TextContent)?.text ?: request.body.toString()
                sent += Sent(request.url.encodedPath, request.headers.build(), body)
                throw Stopped()
            }
        }
    }

    @BeforeTest
    fun signIn() {
        Innertube.cookie = COOKIE
        Innertube.adoptSessionScope(
            pageId = null, dataSyncId = SHELL_DSID, authUser = "1",
            visitorData = SESSION_VISITOR, clientVersion = "1.20260101.01.00", loggedIn = true,
        )
        Innertube.selectChannel(pageId = "chosen-page", dataSyncId = CHOSEN_DSID, authUser = "1")
    }

    @AfterTest
    fun signOut() {
        Innertube.cookie = null
    }

    /** The Innertube requests [block] makes, each stopped before it is sent. */
    private fun requestsOf(block: suspend () -> Unit): List<Sent> {
        sent.clear()
        runBlocking { runCatching { block() } }
        return sent.filter { it.path.startsWith("/youtubei/") }
    }

    private fun assertAnonymous(name: String, block: suspend () -> Unit) {
        val requests = requestsOf(block)
        assertTrue(requests.isNotEmpty(), "$name sent nothing")
        requests.forEach { r ->
            val what = "$name (${r.path})"
            assertNull(r.headers["Cookie"], "$what carried the cookie")
            assertNull(r.headers["Authorization"], "$what was signed")
            assertNull(r.headers["X-Goog-AuthUser"], "$what named an account")
            assertNull(r.headers["X-Goog-PageId"], "$what named a channel")
            assertFalse("onBehalfOfUser" in r.body, "$what spoke for a user: ${r.body}")
            assertFalse(SHELL_DSID in r.body || CHOSEN_DSID in r.body, "$what carried an account id")
            assertFalse(SESSION_VISITOR == r.headers["X-Goog-Visitor-Id"], "$what sent the session's visitor id")
            assertFalse(SESSION_VISITOR in r.body, "$what sent the session's visitor id in its body")
        }
    }

    private fun assertSigned(name: String, block: suspend () -> Unit) {
        val requests = requestsOf(block)
        assertTrue(requests.isNotEmpty(), "$name sent nothing")
        requests.forEach { r ->
            val what = "$name (${r.path})"
            assertEquals(COOKIE, r.headers["Cookie"], "$what lost the cookie")
            assertTrue(r.headers["Authorization"]?.startsWith("SAPISIDHASH ") == true, "$what was not signed")
            assertEquals("chosen-page", r.headers["X-Goog-PageId"], "$what lost the chosen channel")
            assertTrue(CHOSEN_DSID in r.body, "$what lost onBehalfOfUser: ${r.body}")
            assertEquals(SESSION_VISITOR, r.headers["X-Goog-Visitor-Id"], "$what lost the session's visitor id")
        }
    }

    // ---- Catalogue: as nobody -------------------------------------------

    @Test
    fun homeShelvesThatAreCatalogueAreAskedAsNobody() {
        assertAnonymous("quickPicks") { YtMusicRepository.quickPicks() }
        assertAnonymous("charts") { YtMusicRepository.charts() }
        assertAnonymous("homeSupplement") { YtMusicRepository.homeSupplement("FEmusic_new_releases") }
        assertAnonymous("moodAndGenres") { YtMusicRepository.moodAndGenres() }
        assertAnonymous("moodGenreShelves") { YtMusicRepository.moodGenreShelves("FEmusic_moods_and_genres_category", "p") }
    }

    @Test
    fun radioArtistCardsAndQueuesAreAskedAsNobody() {
        assertAnonymous("radio") { YtMusicRepository.radio("dQw4w9WgXcQ") }
        assertAnonymous("radio as videos") { YtMusicRepository.radio("dQw4w9WgXcQ", preferVideo = true) }
        assertAnonymous("artistLanding") { YtMusicRepository.artistLanding("UCartist") }
        assertAnonymous("watchQueue") { YtMusicRepository.watchQueue(WatchTarget("RDAMPLx", "dQw4w9WgXcQ", "p")) }
        assertAnonymous("playlistAsAudio") { YtMusicRepository.playlistAsAudio("PLeditorial") }
        assertAnonymous("trackLinks") { YtMusicRepository.trackLinks("dQw4w9WgXcQ") }
    }

    @Test
    fun searchAndLyricsAreAskedAsNobody() {
        assertAnonymous("searchPage") { YtMusicRepository.searchPage("query", SearchFilter.SONGS) }
        assertAnonymous("searchContinuation") { YtMusicRepository.searchContinuation("token", SearchFilter.SONGS) }
        assertAnonymous("searchTypeahead") { YtMusicRepository.searchTypeahead("que") }
        assertAnonymous("searchSuggestions") { YtMusicRepository.searchSuggestions("que") }
        assertAnonymous("findArtistPageId") { YtMusicRepository.findArtistPageId("Some Artist") }
        assertAnonymous("YouTube Music lyrics") { YouTubeMusicLyrics.lyrics("dQw4w9WgXcQ") }
        assertAnonymous("transcript") { Innertube.transcript("dQw4w9WgXcQ") }
    }

    // ---- The account's own: signed ----------------------------------------

    @Test
    fun theAccountsOwnLibraryAndWritesStaySigned() {
        // The default is signed, so a library read is never answered for nobody — an empty answer there would
        // read as an empty library.
        assertSigned("browse by default") { Innertube.browse("FEmusic_liked_playlists") }
        assertSigned("browseSongs of a playlist") { YtMusicRepository.browseSongs("VLPLprivate") }
        assertSigned("libraryAlbums") { YtMusicRepository.libraryAlbums() }
        assertSigned("history") { YtMusicRepository.history() }
        assertSigned("songMenu") { YtMusicRepository.songMenu("dQw4w9WgXcQ") }
        assertSigned("rate") { YtMusicRepository.rate("dQw4w9WgXcQ", LikeStatus.LIKE) }
    }

    @Test
    fun anAnonymousReadAndASignedOneSideBySideEachKeepTheirOwnScope() {
        // The process-wide cookie is not flipped for an anonymous read, so a signed read made alongside it — an
        // import running while the DJ asks for a radio — is still made as the account.
        val requests = requestsOf {
            runCatching { YtMusicRepository.radio("dQw4w9WgXcQ") }
            runCatching { YtMusicRepository.libraryAlbums() }
            runCatching { YtMusicRepository.radio("dQw4w9WgXcQ") }
        }
        assertEquals(listOf(false, true, false), requests.map { it.headers["Cookie"] != null })
        assertEquals(COOKIE, Innertube.cookie)
    }
}
