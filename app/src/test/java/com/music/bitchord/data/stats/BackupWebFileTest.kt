package com.music.bitchord.data.stats

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * What the WEB writes, read by the phone's own deserializer.
 *
 * The web port (`web/app/src/auden/backup`) writes `auden-backup-*.json` in the shape [Backup] reads. Its own
 * tests can only show that its reading of these declarations agrees with its writing; this one asks the real
 * kotlinx decoder, with the same [Json] configuration as [Backup], whether the phone would accept the files.
 * The fixtures are committed on the web side; `web-export.generated.json` is produced by its round-trip test
 * (Android golden -> web import -> web export) and that test fails when it is stale.
 */
class BackupWebFileTest {
    // The same configuration as Backup.json (private there).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private fun fixture(name: String): String {
        // Gradle runs unit tests with the module directory (app/) as the working directory.
        val file = File("../web/app/src/auden/backup/fixtures/$name")
        assertTrue("missing fixture ${file.absolutePath}", file.exists())
        return file.readText()
    }

    private fun decode(name: String) = json.decodeFromString(Backup.BackupFile.serializer(), fixture(name))

    /** Mirrors SpotifyMatches.Stored (private there): the same names, nullability and defaults. */
    @Serializable
    private data class StoredSong(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val albumName: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val isVideo: Boolean = false,
        val isExplicit: Boolean? = null,
    )

    private fun assertLibraryIsRestorable(file: Backup.BackupFile) {
        val lib = file.library ?: return
        // Room's replaceAll enforces the foreign keys: one dangling entry fails the whole restore.
        val trackIds = lib.tracks.map { it.id }
        val playlistIds = lib.playlists.map { it.id }
        assertEquals("track ids are unique", trackIds.size, trackIds.toSet().size)
        assertTrue("a track id of 0 means 'assign one' to Room", trackIds.all { it >= 1 })
        assertEquals("playlist ids are unique", playlistIds.size, playlistIds.toSet().size)
        for (e in lib.entries) {
            assertTrue("entry ${e.entryId} points at a missing track ${e.trackId}", e.trackId in trackIds)
            assertTrue("entry ${e.entryId} points at a missing playlist ${e.playlistId}", e.playlistId in playlistIds)
            assertTrue(e.entryId >= 1)
        }
        // `localUri` is a unique index: a duplicate makes the phone's REPLACE delete the earlier row.
        val uris = lib.tracks.mapNotNull { it.localUri }
        assertEquals("a file uri appears once", uris.size, uris.toSet().size)
        // Positions are dense and zero-based per playlist.
        for ((_, entries) in lib.entries.groupBy { it.playlistId }) {
            assertEquals(entries.indices.toList(), entries.sortedBy { it.position }.map { it.position })
        }
        assertFalse("a web playlist id would miss the phone's lookups", playlistIds.any { it.startsWith("local:playlist:") })
        for (m in lib.spotifyMatches) {
            // songJson is read with no runCatching on the phone (SpotifyMatches.cached): it must decode.
            m.songJson?.let { assertNotNull(json.decodeFromString(StoredSong.serializer(), it)) }
        }
    }

    @Test
    fun theAndroidGoldenFileReads() {
        val file = decode("android-golden.json")
        assertEquals("auden", file.app)
        assertLibraryIsRestorable(file)
        assertEquals(2, file.listening.size)
    }

    @Test
    fun aFileTheWebWroteFromThatGoldenReads() {
        val file = decode("web-export.generated.json")
        assertEquals("auden", file.app)
        assertLibraryIsRestorable(file)
        // The phone's settings went out and come back untouched: a restore replaces its whole preference file.
        val golden = decode("android-golden.json")
        assertEquals(golden.settings, file.settings)
        assertEquals(golden.library!!.tracks.map { it.localUri }.sortedBy { it ?: "" }, file.library!!.tracks.map { it.localUri }.sortedBy { it ?: "" })
        assertEquals(golden.library!!.lyricsVariants.size, file.library!!.lyricsVariants.size)
        assertEquals(golden.listening.map { it.month }.sorted(), file.listening.map { it.month })
    }

    @Test
    fun aFileMadeOnTheWebWithNoPhoneSettingsReads() {
        val file = decode("web-born.json")
        assertLibraryIsRestorable(file)
        // Reads, and says what a restore would do: with no settings the phone's would be replaced by none.
        assertTrue(file.settings.isEmpty())
    }

    @Test
    fun aBitChordFileStillReadsAndTouchesNoLibrary() {
        val file = decode("bitchord-v1.json")
        assertEquals("bitchord", file.app)
        assertNull(file.library)
    }

    @Test
    fun aFractionalMillisecondIsRefusedByThePhone() {
        // What the web must never write: kotlinx refuses 5000.3 for a Long, so one such value rejects the whole file.
        val broken = fixture("web-export.generated.json").replace(Regex("\"ms\": \\d+"), "\"ms\": 5000.3")
        assertTrue("the fixture has a listening ms to break", broken != fixture("web-export.generated.json"))
        try {
            json.decodeFromString(Backup.BackupFile.serializer(), broken)
            fail("a fractional Long was accepted")
        } catch (_: SerializationException) {
            // Expected.
        }
    }
}
