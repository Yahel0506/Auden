package com.music.bitchord

import com.music.bitchord.data.YtPlaylistSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtPlaylistSyncTest {
    private fun e(vararg pairs: Pair<String, String>) = pairs.toList()

    @Test
    fun `an identical playlist needs no change`() {
        val plan = YtPlaylistSync.plan(e("s1" to "a", "s2" to "b"), listOf("a", "b"))
        assertEquals(emptyList<Pair<String, String>>(), plan.remove)
        assertEquals(emptyList<String>(), plan.add)
        assertEquals(listOf("s1", "s2"), plan.keptInTargetOrder)
    }

    @Test
    fun `only the difference is removed and added`() {
        val plan = YtPlaylistSync.plan(e("s1" to "a", "s2" to "b", "s3" to "c"), listOf("a", "c", "d"))
        assertEquals(listOf("s2" to "b"), plan.remove)
        assertEquals(listOf("d"), plan.add)
        assertEquals(listOf("s1", "s3"), plan.keptInTargetOrder)
    }

    @Test
    fun `a song listed twice matches twice and an extra copy is removed`() {
        val plan = YtPlaylistSync.plan(e("s1" to "a", "s2" to "a", "s3" to "a"), listOf("a", "a"))
        assertEquals(listOf("s3" to "a"), plan.remove)
        assertEquals(listOf("s1", "s2"), plan.keptInTargetOrder)
    }

    @Test
    fun `entries are put in the target's order`() {
        assertEquals(listOf("s2", "s1"), YtPlaylistSync.orderTo(e("s1" to "a", "s2" to "b"), listOf("b", "a")))
        assertNull(YtPlaylistSync.orderTo(e("s1" to "a", "s2" to "x"), listOf("a", "b")))
    }

    // ---- The export's confirmation (phase 5) ------------------------------------------------------------------

    @Test
    fun `with no linked playlist an export creates one with every song`() {
        assertEquals(
            YtPlaylistSync.ExportSummary(create = true, add = 3, remove = 0, reorder = false, skipped = 2, total = 3),
            YtPlaylistSync.summarize(null, listOf("a", "b", "c"), skipped = 2),
        )
    }

    @Test
    fun `an update names what it would remove on YouTube Music`() {
        val summary = YtPlaylistSync.summarize(e("s1" to "a", "s2" to "b", "s3" to "c"), listOf("a", "c", "d"), skipped = 0)
        assertEquals(YtPlaylistSync.ExportSummary(create = false, add = 1, remove = 1, reorder = false, skipped = 0, total = 3), summary)
    }

    @Test
    fun `a new song that would not land at the end, or a moved one, is a reorder`() {
        assertEquals(true, YtPlaylistSync.summarize(e("s1" to "a", "s2" to "b"), listOf("b", "a"), 0).reorder)
        assertEquals(true, YtPlaylistSync.summarize(e("s1" to "a", "s2" to "b"), listOf("a", "x", "b"), 0).reorder)
        assertEquals(false, YtPlaylistSync.summarize(e("s1" to "a", "s2" to "b"), listOf("a", "b", "x"), 0).reorder)
        val same = YtPlaylistSync.summarize(e("s1" to "a", "s2" to "b"), listOf("a", "b"), 0)
        assertEquals(YtPlaylistSync.ExportSummary(create = false, add = 0, remove = 0, reorder = false, skipped = 0, total = 2), same)
    }
}
