package com.music.bitchord.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playlistsIncludeFolderLeaves() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:abc","data":{
                "__typename":"Playlist","name":"Road","ownerV2":{"data":{"name":"Amit"}},
                "images":{"items":[{"sources":[{"url":"https://i.scdn.co/x"}]}]}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val (items, total) = parsePlaylistPage(root)
        assertEquals(1, total)
        assertEquals("abc", items.single().id)
        assertEquals("Road", items.single().name)
        assertEquals("Amit", items.single().owner)
        assertEquals("https://i.scdn.co/x", items.single().imageUrl)
    }

    @Test
    fun tracksReadTitleArtistAndDuration() {
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":1,"items":[
              {"itemV2":{"data":{
                "name":"Song","uri":"spotify:track:t1",
                "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
                "albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"https://img"}]}},
                "duration":{"totalMilliseconds":180000}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val track = parseTrackPage(root).first.single()
        assertEquals("t1", track.id)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(180000, track.durationMs)
        assertEquals("https://img", track.imageUrl)
    }

    @Test
    fun playlistIdsComeFromLinksAndUris() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("  https://open.spotify.com/playlist/$id?si=abc123  "))
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/intl-id/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("spotify:playlist:$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://notspotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("ftp://open.spotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/short"))
        assertNull(SpotifyImporter.extractPlaylistId(""))
    }

    @Test
    fun albumsReadNameArtistAndWidestCover() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":2,"items":[
              {"item":{"__typename":"AlbumResponseWrapper","_uri":"spotify:album:al1","data":{
                "__typename":"Album","name":"Record",
                "artists":{"items":[{"profile":{"name":"Band"}},{"profile":{"name":"Guest"}}]},
                "coverArt":{"sources":[{"url":"https://small","width":64},{"url":"https://big","width":640}]}
              }}},
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:p","data":{"__typename":"Playlist","name":"Not an album"}}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val (items, total, raw) = parseAlbumPage(root)
        assertEquals(2, total)
        assertEquals(2, raw)
        val album = items.single()
        assertEquals("al1", album.id)
        assertEquals("Record", album.name)
        assertEquals("Band, Guest", album.artist)
        assertEquals("https://big", album.imageUrl)
    }

    @Test
    fun artistsReadNameAndAvatar() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
              {"item":{"__typename":"ArtistResponseWrapper","_uri":"spotify:artist:ar1","data":{
                "__typename":"Artist","profile":{"name":"Singer"},
                "visuals":{"avatarImage":{"sources":[{"url":"https://face","width":320}]}}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val artist = parseArtistPage(root).first.single()
        assertEquals("ar1", artist.id)
        assertEquals("Singer", artist.name)
        assertEquals("https://face", artist.imageUrl)
    }

    @Test
    fun anUnexpectedLibraryShapeIsAnErrorNotACrash() {
        val root = json.parseToJsonElement("""{"data":{}}""").jsonObject
        val failed = runCatching { parseAlbumPage(root) }.exceptionOrNull()
        assertEquals(true, failed is IllegalStateException)
    }
}
