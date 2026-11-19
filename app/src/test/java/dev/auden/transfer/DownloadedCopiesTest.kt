package dev.auden.transfer

import com.music.bitchord.download.SavedCollection
import com.music.bitchord.download.SavedSongMetadata
import dev.auden.library.Service
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedCopiesTest {
    private fun record(id: String, playlist: Boolean = true, vararg videoIds: String) =
        SavedCollection(id = id, title = "T $id", playlist = playlist, videoIds = videoIds.toList(), thumbnailUrl = "file:///art/$id.jpg")

    private val records = listOf(
        record("VLPL1", true, "a", "b"),
        record("PL2", true, "c"),
        record("VLLM", true, "l"),
        record("MPREb_1", false, "d"),
        record("local:playlist:sp_local_9", true, "e"),
        record("spotify:playlist:abc", true, "f"),
    )

    @Test
    fun onlyTheYouTubePlaylistsPlaylistSyncReReadsButLikedMusic() {
        assertEquals(listOf("PL1", "PL2"), DownloadedCopies.remote(records).map { it.sourceId })
        assertEquals(listOf("VLLM"), DownloadedCopies.likesRecords(records).map { it.id })
    }

    @Test
    fun eachGetsACopyOnceFromTheSourceAndAFailingOneFromTheDeviceInTheEnd() = runBlocking {
        val library = FakeLibrary()
        val source = FakeSource(Service.YT_MUSIC, tracks = mapOf("PL1" to yt("a", "b", "new")))
        val engine = ImportEngine(library, FakeResolver(), source, now = { 5L })
        val failures = mutableMapOf<String, Int>()
        val attempts = DownloadedCopies.Attempts { id -> (failures.getOrDefault(id, 0) + 1).also { failures[id] = it } }
        val metadata = mapOf("c" to SavedSongMetadata(videoId = "c", title = "Cee", artist = "Art", uri = "content://c", thumbnailUrl = "https://c"))

        val first = DownloadedCopies.convert(engine, source, records, metadata, library, attempts, maxAttempts = 2)
        assertEquals(DownloadedCopies.Result(copied = 1, alreadyThere = 0, fromDevice = 0, pending = 1), first)
        val fromSource = library.copies.values.single()
        assertEquals("PL1", fromSource.sourceId)
        assertEquals(listOf("a", "b", "new"), fromSource.origin.snapshot)
        // A local artwork file is not a cover a copy can keep.
        assertNull(fromSource.cover)

        // PL2 cannot be read (deleted online, say): the second failure makes it from its downloads, with no snapshot.
        val second = DownloadedCopies.convert(engine, source, records, metadata, library, attempts, maxAttempts = 2)
        assertEquals(DownloadedCopies.Result(copied = 0, alreadyThere = 1, fromDevice = 1, pending = 0), second)
        val fromDevice = library.copies.values.last()
        assertEquals("PL2", fromDevice.sourceId)
        assertEquals(listOf("c"), fromDevice.songs.map { it.videoId })
        assertEquals("Cee", fromDevice.songs.single().title)
        assertNull(fromDevice.origin.snapshot)
        assertNull(fromDevice.origin.syncedAt)

        val third = DownloadedCopies.convert(engine, source, records, metadata, library, attempts, maxAttempts = 2)
        assertEquals(DownloadedCopies.Result(copied = 0, alreadyThere = 2, fromDevice = 0, pending = 0), third)
        assertEquals(2, library.copies.size)
    }
}
