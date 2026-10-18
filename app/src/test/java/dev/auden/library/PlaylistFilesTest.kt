package dev.auden.library

import dev.auden.library.db.TrackEntity
import dev.auden.library.io.AudenJson
import dev.auden.library.io.M3u
import dev.auden.library.io.Paths
import dev.auden.library.io.PlaylistEntry
import dev.auden.library.io.PlaylistFormats
import dev.auden.library.io.PlaylistMatcher
import dev.auden.library.io.Xspf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFilesTest {
    private val entries = listOf(
        PlaylistEntry(path = "/music/Rock/a.mp3", title = "Song A", artist = "Band", durationSec = 215.0),
        PlaylistEntry(path = "/music/Pop/b & c.flac", title = "B", artist = "Singer", durationSec = null),
    )

    @Test
    fun m3u_roundTripsRelativeToItsFolder() {
        val text = M3u.write("Mix", entries, "/music/Playlists")
        assertTrue(text.contains("../Rock/a.mp3"))
        assertTrue(text.startsWith("#EXTM3U"))
        val back = M3u.parse(text, "/music/Playlists")
        assertEquals("Mix", back.name)
        assertEquals(entries.map { it.path }, back.entries.map { it.path })
        assertEquals("Band", back.entries[0].artist)
        assertEquals("Song A", back.entries[0].title)
        assertEquals(215.0, back.entries[0].durationSec!!, 0.01)
    }

    @Test
    fun m3u_readsWindowsPathsAndUrls() {
        val parsed = M3u.parse("#EXTM3U\r\nsub\\x.mp3\r\nhttps://h/x.mp3\r\n", "/m")
        assertEquals("/m/sub/x.mp3", parsed.entries[0].path)
        assertTrue(parsed.entries[1].isUrl)
    }

    @Test
    fun xspf_roundTripsAndEscapes() {
        val text = Xspf.write("A & B", entries, "/music/Playlists")
        val back = Xspf.parse(text, "/music/Playlists")
        assertEquals("A & B", back.name)
        assertEquals(entries.map { it.path }, back.entries.map { it.path })
        assertEquals(215.0, back.entries[0].durationSec!!, 0.01)
    }

    @Test
    fun json_keepsVideoIds() {
        val text = AudenJson.write("Streamed", listOf(PlaylistEntry(title = "T", artist = "A", videoId = "abc")))
        assertEquals("abc", AudenJson.parse(text)!!.entries.single().videoId)
        assertEquals("abc", PlaylistFormats.parse("x.json", text, null)!!.entries.single().videoId)
    }

    @Test
    fun paths_helpers() {
        assertEquals("../b/c.mp3", Paths.relativize("/a/b/c.mp3", "/a/x"))
        assertEquals("/a/c", Paths.normalize("/a/b/../c"))
        assertEquals("a_b", Paths.safeFileName("a:b"))
        val taken = setOf("Mix.m3u", "Mix (2).m3u")
        assertEquals("Mix (3).m3u", Paths.uniqueName("Mix", "m3u", taken::contains))
    }

    private fun track(id: Long, path: String, title: String, artist: String = "Band", ms: Long? = null, videoId: String? = null) =
        TrackEntity(id = id, videoId = videoId, path = path, localUri = "content://$id", title = title, artist = artist, durationMs = ms)

    @Test
    fun matcher_prefersExactPathThenFileNameThenTags() {
        val library = listOf(
            track(1, "/storage/sd/Rock/a.mp3", "Song A", ms = 215_000),
            track(2, "/storage/sd/Pop/a.mp3", "Other"),
            track(3, "/x/zzz.mp3", "Café Déjà Vu (Remastered)", "Artista"),
        )
        val matcher = PlaylistMatcher(library)
        assertEquals(1L, matcher.match(PlaylistEntry(path = "/storage/sd/Rock/a.mp3"))!!.id)
        // Moved from another device: same name, the Rock folder shares the longer tail.
        assertEquals(1L, matcher.match(PlaylistEntry(path = "/sdcard/Music/Rock/a.mp3"))!!.id)
        assertEquals(3L, matcher.match(PlaylistEntry(path = "/gone.mp3", title = "cafe deja vu", artist = "Artista"))!!.id)
        assertNull(matcher.match(PlaylistEntry(path = "/gone.mp3", title = "Unknown", artist = "x")))
    }

    @Test
    fun matcher_rejectsATitleWhoseLengthIsFarOff() {
        val matcher = PlaylistMatcher(listOf(track(1, "/a.mp3", "Intro", ms = 30_000)))
        assertNull(matcher.match(PlaylistEntry(title = "Intro", artist = "Band", durationSec = 300.0)))
    }
}

class SongIdentityTest {
    private fun song(id: String, title: String, artist: String, length: String? = null, uri: String? = null) =
        com.music.bitchord.data.model.Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length, localUri = uri)

    @Test
    fun sameId_isTheSameSong() {
        org.junit.Assert.assertTrue(dev.auden.library.SongIdentity.same(song("a", "x", "y"), song("a", "other", "z")))
    }

    @Test
    fun differentIdsButSameMetadata_match() {
        val album = song("1", "Café Déjà Vu (Remastered 2011)", "The Band", "3:45")
        val video = song("2", "cafe deja vu", "The Band, Guest", "3:46")
        org.junit.Assert.assertTrue(dev.auden.library.SongIdentity.same(album, video))
    }

    @Test
    fun aLiveTakeOrCoverIsNotTheOriginal() {
        val studio = song("1", "Song", "Band", "3:45")
        org.junit.Assert.assertFalse(dev.auden.library.SongIdentity.same(studio, song("2", "Song", "Band", "7:10")))
        org.junit.Assert.assertFalse(dev.auden.library.SongIdentity.same(studio, song("3", "Song", "Someone Else", "3:45")))
        org.junit.Assert.assertFalse(dev.auden.library.SongIdentity.same(studio, song("4", "Different", "Band", "3:45")))
    }

    @Test
    fun aFileAndItsOwnUri_match() {
        org.junit.Assert.assertTrue(
            dev.auden.library.SongIdentity.same(song("c1", "A", "B", uri = "content://1"), song("c2", "Z", "Y", uri = "content://1")),
        )
    }
}
