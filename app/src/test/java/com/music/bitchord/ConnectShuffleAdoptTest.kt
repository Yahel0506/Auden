package com.music.bitchord

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.playback.QueueShuffle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shuffle across devices: a queue that arrives shuffled brings the order it had before, so turning shuffle off here
 * lands on the same list as everywhere else, and a phone that publishes a shuffled queue says that order.
 */
class ConnectShuffleAdoptTest {

    @After
    fun reset() = QueueShuffle.adoptUnshuffled()

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun player(items: List<MediaItem>): Player = java.lang.reflect.Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args[0] as Int]
            else -> null
        }
    } as Player

    @Test
    fun adoptingAShuffledQueue_remembersTheSendersOrder() {
        val shuffled = listOf("c", "a", "d", "b").map(::item)
        QueueShuffle.adoptShared(shuffled, listOf("a", "b", "c", "d"))
        assertTrue(QueueShuffle.enabled.value)
        assertEquals(listOf("a", "b", "c", "d"), QueueShuffle.canonicalContext.map { it.mediaId })
        assertEquals(4, QueueShuffle.originalOrder.size)
        assertEquals(listOf("a", "b", "c", "d"), QueueShuffle.originalOrder.map { it.substringBefore('_') })
    }

    @Test
    fun theAdoptedOrder_isTheOneThePhonePublishesBack() {
        val shuffled = listOf("c", "a", "d", "b").map(::item)
        QueueShuffle.adoptShared(shuffled, listOf("a", "b", "c", "d"))
        val out = QueueShuffle.sharedOrder(player(shuffled), listOf("c", "a", "d", "b"))
        assertEquals(listOf("a", "b", "c", "d"), out)
        // Only the published window's songs, and songs queued after the shuffle at the end.
        val later = shuffled + item("z")
        assertEquals(listOf("a", "c", "z"), QueueShuffle.sharedOrder(player(later), listOf("c", "a", "z")))
    }

    @Test
    fun withoutAnOrder_onlyTheFlagFollows_andNothingIsPublished() {
        val q = listOf("c", "a").map(::item)
        QueueShuffle.adoptShared(q, null)
        assertTrue(QueueShuffle.enabled.value)
        assertEquals(emptyList<MediaItem>(), QueueShuffle.canonicalContext)
        assertNull(QueueShuffle.sharedOrder(player(q), listOf("c", "a")))
    }

    @Test
    fun anUnshuffledQueue_forgetsTheOrderAndTheFlag() {
        QueueShuffle.adoptShared(listOf("b", "a").map(::item), listOf("a", "b"))
        QueueShuffle.adoptUnshuffled()
        assertFalse(QueueShuffle.enabled.value)
        assertEquals(emptyList<MediaItem>(), QueueShuffle.canonicalContext)
        assertNull(QueueShuffle.sharedOrder(player(listOf(item("a"))), listOf("a")))
    }
}
