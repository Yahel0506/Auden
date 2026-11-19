package dev.auden.transfer

import dev.auden.library.CloudSaver
import dev.auden.library.LikeListClassifier
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistOrigin
import dev.auden.library.PlaylistSnapshot
import dev.auden.library.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOriginTest {

    @Test
    fun youTubeMusicIdsReadAsCloudSaverWroteThem() {
        val playlist = PlaylistOrigin.of(CloudSaver.sourceId("VLPLabc"))!!
        assertEquals(PlaylistOrigin(Service.YT_MUSIC, OriginKind.PLAYLIST, "PLabc", "PLabc"), playlist)
        assertEquals("VLPLabc", playlist.browseId)

        // With or without the VL, the same origin.
        assertEquals(playlist, PlaylistOrigin.of("VLPLabc"))

        val album = PlaylistOrigin.of("MPREb_xyz")!!
        assertEquals(OriginKind.ALBUM, album.kind)
        assertEquals("MPREb_xyz", album.browseId)

        val albumPlaylist = PlaylistOrigin.of("OLAK5uy_abc")!!
        assertEquals(OriginKind.ALBUM, albumPlaylist.kind)
        assertEquals("VLOLAK5uy_abc", albumPlaylist.browseId)
    }

    @Test
    fun spotifyIds() {
        val playlist = PlaylistOrigin.of("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")!!
        assertEquals(Service.SPOTIFY, playlist.service)
        assertEquals(OriginKind.PLAYLIST, playlist.kind)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", playlist.remoteId)
        assertEquals("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", playlist.browseId)

        val album = PlaylistOrigin.of("spotify:album:4aawyAB9vmqN3uQ7FjRGTy")!!
        assertEquals(Service.SPOTIFY, album.service)
        assertEquals(OriginKind.ALBUM, album.kind)
    }

    @Test
    fun likesListsAreLikesWhereverTheyComeFrom() {
        listOf("LM", "VLLM", CloudSaver.sourceId("VLLM"), "spotify:playlist:liked").forEach { id ->
            val origin = PlaylistOrigin.of(id)!!
            assertEquals(id, OriginKind.LIKES, origin.kind)
            assertTrue(id, origin.isLikesList)
        }
        assertTrue(LikeListClassifier.isLikesList(Service.YT_MUSIC, "LM"))
        assertTrue(LikeListClassifier.isLikesList(Service.YT_MUSIC, "VLLM"))
        assertTrue(LikeListClassifier.isLikesList(Service.SPOTIFY, "liked"))
        assertTrue(LikeListClassifier.isLikesList(Service.SPOTIFY, "spotify:playlist:liked"))
        // Close is not the same.
        assertFalse(LikeListClassifier.isLikesList(Service.YT_MUSIC, "LMX"))
        assertFalse(LikeListClassifier.isLikesList(Service.YT_MUSIC, "liked"))
        assertFalse(LikeListClassifier.isLikesList(Service.SPOTIFY, "LM"))
        assertFalse(LikeListClassifier.isLikesList(Service.SPOTIFY, null))
        assertFalse(PlaylistOrigin.of("PLlikedish")!!.isLikesList)
    }

    @Test
    fun noSourceOrAnUnknownShapeIsNoOrigin() {
        assertNull(PlaylistOrigin.of(null))
        assertNull(PlaylistOrigin.of(""))
        assertNull(PlaylistOrigin.of("  "))
        assertNull(PlaylistOrigin.of("VL"))
        assertNull(PlaylistOrigin.of("spotify:playlist:"))
        assertNull(PlaylistOrigin.of("local:playlist:sp_local_1"))
        assertNull(PlaylistOrigin.of("file:///sdcard/x.m3u"))
    }

    @Test
    fun snapshotRoundTripsAndAnUnreadableOneIsNone() {
        val ids = listOf("a", "b\"quoted", "c")
        assertEquals(ids, PlaylistSnapshot.decode(PlaylistSnapshot.encode(ids)))
        assertEquals(emptyList<String>(), PlaylistSnapshot.decode(PlaylistSnapshot.encode(emptyList())))
        assertNull(PlaylistSnapshot.decode(null))
        assertNull(PlaylistSnapshot.decode("not json"))
    }
}
