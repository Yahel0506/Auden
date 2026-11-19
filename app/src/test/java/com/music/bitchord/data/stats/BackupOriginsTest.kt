package com.music.bitchord.data.stats

import dev.auden.library.db.FollowedArtistEntity
import dev.auden.library.db.PlaylistEntity
import dev.auden.library.db.PlaylistOriginEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The v9 tables in the backup file. The restore itself (BackupDao.replaceAll) is in AudenDbMigrationTest. */
class BackupOriginsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun originsAndFollowedArtistsComeBackAsTheyLeft() {
        val library = Backup.LibraryData(
            playlists = listOf(PlaylistEntity(id = "sp_local_1", title = "Mix", sourceId = "spotify:playlist:abc")),
            playlistOrigins = listOf(PlaylistOriginEntity("sp_local_1", snapshot = """["t1","t2"]""", syncedAt = 9, originTitle = "Mix")),
            followedArtists = listOf(FollowedArtistEntity("UC1", "One", thumb = "https://t", originService = "YT_MUSIC", originId = "UC1", addedAt = 3)),
        )
        val text = json.encodeToString(Backup.BackupFile.serializer(), Backup.BackupFile(library = library))
        assertEquals(library, json.decodeFromString(Backup.BackupFile.serializer(), text).library)
    }

    @Test
    fun aLibraryFromBeforeThemReadsAsNoneSoTheRestoreKeepsTheDevicesOwn() {
        val old = """{"app":"auden","version":2,"library":{"tracks":[],"playlists":[{"id":"p","title":"Mix","createdAt":1,"updatedAt":1,"pinned":false}]}}"""
        val library = json.decodeFromString(Backup.BackupFile.serializer(), old).library!!
        assertEquals("p", library.playlists.single().id)
        assertNull(library.playlistOrigins)
        assertNull(library.followedArtists)
    }
}
