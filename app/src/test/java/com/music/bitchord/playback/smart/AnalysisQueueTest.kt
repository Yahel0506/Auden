package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AnalysisQueueTest {

    /** Holds the worker on a first task so that everything after it is queued before any is picked. */
    private fun run(rank: (String?) -> Int, queue: (AnalysisQueue, (String) -> () -> Unit) -> Unit): List<String> {
        val order = CopyOnWriteArrayList<String>()
        val gate = CountDownLatch(1)
        val done = CountDownLatch(1)
        val q = AnalysisQueue("test-analysis", rank)
        q.execute("held") { gate.await(5, TimeUnit.SECONDS) }
        queue(q) { name -> { order += name } }
        q.execute("last") { done.countDown() }
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        q.shutdownNow()
        return order
    }

    private val ranks = mapOf("now" to 0, "next" to 1, "gone" to -2, "held" to 0, "last" to 99)

    @Test
    fun `the track playing goes before the ones that asked earlier`() {
        val order = run({ id -> if (id == null) -1 else ranks.getValue(id) }) { q, task ->
            q.execute("next", task("next"))
            q.execute("now", task("now"))
        }
        assertEquals(listOf("now", "next"), order)
    }

    @Test
    fun `a track nobody wants is taken out of the way first`() {
        val order = run({ id -> if (id == null) -1 else ranks.getValue(id) }) { q, task ->
            q.execute("now", task("now"))
            q.execute("gone", task("gone"))
        }
        assertEquals(listOf("gone", "now"), order)
    }

    @Test
    fun `work that belongs to no track goes first, and equal ranks keep their order`() {
        val order = run({ id -> if (id == null) -1 else 0 }) { q, task ->
            q.execute("a", task("a"))
            q.execute(null, task("loose"))
            q.execute("b", task("b"))
        }
        assertEquals(listOf("loose", "a", "b"), order)
    }
}
