package com.music.bitchord.playback

import com.music.bitchord.data.model.DJ_CLIP_PREFIX
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LastPlayedClipsTest {
    private fun song(id: String) = Song(videoId = id, title = id, artist = "a", thumbnailUrl = null)
    private val clip = { n: Int -> song("$DJ_CLIP_PREFIX$n") }

    @Test
    fun voiceLinesAreLeftOutOfARestartSnapshotAndTheIndexFollowsTheSongInHand() {
        val queue = listOf(clip(1), song("A"), song("B"), clip(2), song("C"))
        val (kept, index) = LastPlayed.withoutDjClips(queue, index = 4)
        assertEquals(listOf("A", "B", "C"), kept.map { it.videoId })
        assertEquals(2, index)
    }

    @Test
    fun whenTheCurrentItemIsALineTheSongAfterItIsTheOneInHand() {
        val queue = listOf(song("A"), clip(1), song("B"))
        val (kept, index) = LastPlayed.withoutDjClips(queue, index = 1)
        assertEquals("B", kept[index].videoId)
    }

    @Test
    fun aQueueWithNoLinesIsLeftAsItIs() {
        val queue = listOf(song("A"), song("B"))
        val (kept, index) = LastPlayed.withoutDjClips(queue, index = 1)
        assertEquals(queue, kept)
        assertEquals(1, index)
    }
}
