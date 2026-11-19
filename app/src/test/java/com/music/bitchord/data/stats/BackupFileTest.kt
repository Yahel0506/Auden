package com.music.bitchord.data.stats

import dev.auden.library.db.LyricsVariantEntity
import dev.auden.library.db.PlaylistEntity
import dev.auden.library.db.PlaylistEntryEntity
import dev.auden.library.db.SongLyricsPrefEntity
import dev.auden.library.db.TrackEntity
import dev.auden.profile.AudenProfile
import dev.auden.profile.ProfileStore
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
        // Nothing to restore for these: the import leaves them alone.
        assertNull(file.offlinePlaylists)
        assertNull(file.listenTogether)
    }

    @Test
    fun theLikesTheNicknameAndTheProfileComeBackAsTheyLeft() {
        val profile = AudenProfile(
            id = "id-1", name = "Yael", photo = "https://example.com/a.jpg",
            partyUserId = "0123456789abcdef0123456789abcdef", connectRoom = "d0695fb667171a671dc9e9c054acfcd7",
            createdAt = 42L, migratedFrom = AudenProfile.MIGRATED_YT,
        )
        val offline = mapOf(
            "playlists_json" to Backup.PrefValue.of("""[{"id":"liked","title":"x","songs":[{"videoId":"abc"}]}]""")!!,
            "hidden_account_likes" to Backup.PrefValue.of(setOf("v1", "v2"))!!,
        )
        val file = Backup.BackupFile(
            settings = mapOf(ProfileStore.KEY to Backup.PrefValue.of(AudenProfile.encode(profile))!!),
            offlinePlaylists = offline,
            listenTogether = Backup.ListenTogetherData(nickname = "Yael"),
        )
        val back = json.decodeFromString(Backup.BackupFile.serializer(), json.encodeToString(Backup.BackupFile.serializer(), file))
        assertEquals(offline, back.offlinePlaylists)
        assertEquals(setOf("v1", "v2"), back.offlinePlaylists!!.getValue("hidden_account_likes").decoded())
        assertEquals("Yael", back.listenTogether?.nickname)
        assertEquals(profile, AudenProfile.decode(back.settings.getValue(ProfileStore.KEY).decoded() as String))
        assertEquals(2, back.version)
    }

    @Test
    fun anOlderBuildReadsANewFile() {
        // What a build before these fields sees: the same decoder settings, a class without them.
        val text = json.encodeToString(
            Backup.BackupFile.serializer(),
            Backup.BackupFile(offlinePlaylists = emptyMap(), listenTogether = Backup.ListenTogetherData("n")),
        )
        val old = json.decodeFromString(OldFile.serializer(), text)
        assertEquals("auden", old.app)
    }
}

/** The top of a backup as a build from before the profile, likes and nickname were carried declares it. */
@kotlinx.serialization.Serializable
private data class OldFile(
    val app: String = "",
    val version: Int = 0,
    val settings: Map<String, Backup.PrefValue> = emptyMap(),
)
