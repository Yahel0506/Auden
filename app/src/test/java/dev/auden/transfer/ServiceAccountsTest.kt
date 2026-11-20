package dev.auden.transfer

import com.music.bitchord.data.deezer.DeezerImporter
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service
import dev.auden.transfer.services.CapturedCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAccountsTest {
    @Test
    fun appleAndTidalCopiesAreRecognisedByTheirSourceIds() {
        assertEquals(Service.APPLE_MUSIC, PlaylistOrigin.of("apple:playlist:p.123")?.service)
        assertEquals(OriginKind.ALBUM, PlaylistOrigin.of("apple:album:l.9")?.kind)
        assertEquals(Service.TIDAL, PlaylistOrigin.of("tidal:playlist:abc-def")?.service)
        assertEquals("abc-def", PlaylistOrigin.of("tidal:playlist:abc-def")?.remoteId)
        assertNull(PlaylistOrigin.of("apple:playlist:"))
        // A playlist of these services is never browsed as a YouTube Music page: its key is the source id itself.
        assertEquals("tidal:album:5", PlaylistOrigin.of("tidal:album:5")?.browseId)
    }

    @Test
    fun theirCopiesAreNotRefreshedBecauseTheirSessionsDoNotLast() {
        assertFalse(PlaylistRefresher.canRefresh("apple:playlist:p.1"))
        assertFalse(PlaylistRefresher.canRefresh("tidal:playlist:x"))
        assertTrue(PlaylistRefresher.canRefresh("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun theWebPlayersTokensAreKeptByHostAndOnlyTheNewestFirstIsTried() {
        val c = CapturedCredentials()
        c.addHeader("Authorization", "Bearer " + "a".repeat(30), "https://amp-api.music.apple.com/v1/x")
        c.addHeader("authorization", "Bearer " + "b".repeat(30), "https://api.tidal.com/v1/x")
        c.addHeader("media-user-token", "m".repeat(30), "https://amp-api.music.apple.com/v1/x")
        c.addHeader("authorization", "Basic nope", "https://api.tidal.com/v1/x")
        assertEquals(listOf("a".repeat(30)), c.untriedBearers("apple.com"))
        assertEquals(listOf("b".repeat(30)), c.untriedBearers("tidal.com"))
        assertEquals("m".repeat(30), c.appleUserToken)
        c.markTried("b".repeat(30))
        assertTrue(c.untriedBearers("tidal.com").isEmpty())
        c.clear()
        assertNull(c.appleUserToken)
        assertTrue(c.untriedBearers("apple.com").isEmpty())
    }

    @Test
    fun deezerLinksAreReadForPlaylistsAndAlbums() {
        assertEquals("playlist" to "908622995", DeezerImporter.parse("https://www.deezer.com/en/playlist/908622995?utm=x"))
        assertEquals("album" to "302127", DeezerImporter.parse("https://www.deezer.com/album/302127"))
        assertNull(DeezerImporter.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }
}
