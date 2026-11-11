package com.music.bitchord

import dev.auden.library.io.M3u
import dev.auden.library.io.PlaylistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExportTest {
    @Test
    fun `a streamed song is written as its YouTube Music address, not dropped`() {
        val text = M3u.write(
            "Mix",
            listOf(
                PlaylistEntry(path = "https://music.youtube.com/watch?v=dQw4w9WgXcQ", title = "Never Gonna Give You Up", artist = "Rick Astley", durationSec = 213.0),
                PlaylistEntry(path = "/storage/emulated/0/Music/a.mp3", title = "A", artist = "B"),
            ),
            baseDir = null,
        )
        assertTrue(text.startsWith("#EXTM3U"))
        assertTrue("#EXTINF:213,Rick Astley - Never Gonna Give You Up" in text)
        assertTrue("https://music.youtube.com/watch?v=dQw4w9WgXcQ" in text)
        assertEquals(2, text.lines().count { it.isNotBlank() && !it.startsWith("#") })
    }

    @Test
    fun `reading it back recovers the video id`() {
        val text = "#EXTM3U\n#EXTINF:213,Rick Astley - Never Gonna Give You Up\nhttps://music.youtube.com/watch?v=dQw4w9WgXcQ\n"
        val entry = M3u.parse(text, baseDir = null).entries.single()
        assertEquals("dQw4w9WgXcQ", entry.videoId)
        assertEquals("Rick Astley", entry.artist)
    }
}
