package com.music.bitchord.data.stats

import dev.auden.library.db.LyricsVariantEntity
import dev.auden.library.db.PlaylistEntity
import dev.auden.library.db.PlaylistEntryEntity
import dev.auden.library.db.SongLyricsPrefEntity
import dev.auden.library.db.TrackEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun theLibraryComesBackAsItLeft() {
        val library = Backup.LibraryData(
            tracks = listOf(TrackEntity(id = 7, videoId = "abc", title = "Song", artist = "Artist", localUri = "content://x/1")),
            playlists = listOf(PlaylistEntity(id = "sp_local_1", title = "Mix", pinned = true, sourceId = "VLPL1")),
            entries = listOf(PlaylistEntryEntity(entryId = 3, playlistId = "sp_local_1", trackId = 7, position = 0)),
            lyricsVariants = listOf(LyricsVariantEntity("abc", "LRCLIB", "LRC", "[00:01.00]hi")),
            lyricsPrefs = listOf(SongLyricsPrefEntity("abc", "LRCLIB", -250)),
        )
        val text = json.encodeToString(Backup.BackupFile.serializer(), Backup.BackupFile(library = library))
        val back = json.decodeFromString(Backup.BackupFile.serializer(), text)
        assertEquals(library, back.library)
        assertEquals("auden", back.app)
    }

    @Test
    fun aBitChordFileStillReads() {
        val old = """{"app":"bitchord","version":1,"versionName":"1.8","settings":{},"listening":[]}"""
        val file = json.decodeFromString(Backup.BackupFile.serializer(), old)
        assertEquals("bitchord", file.app)
        assertNull(file.library)
    }
}
