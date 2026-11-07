package com.music.bitchord

import com.music.bitchord.playback.ConnectVolumeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each device keeps its own volume: a hand-over from the web at full volume must not raise the phone's. */
class ConnectVolumeGuardTest {
    private val max = 15

    @Test
    fun putsTheOwnLevelBackWhenTheStreamJumpsToMaximumAfterAHandOver() {
        val g = ConnectVolumeGuard(windowMs = 4_000)
        assertNull(g.tick(0, remote = false, current = 6, max = max)) // the phone's own level, remembered
        assertNull(g.tick(1_000, remote = true, current = 6, max = max)) // mirroring the web
        assertNull(g.tick(2_000, remote = false, current = 6, max = max)) // taken over, nothing moved
        assertEquals(6, g.tick(2_200, remote = false, current = 15, max = max)) // the jump to maximum is undone
    }

    @Test
    fun leavesAnUnmovedStreamAlone() {
        val g = ConnectVolumeGuard()
        g.tick(0, remote = false, current = 6, max = max)
        g.tick(1_000, remote = true, current = 6, max = max)
        assertNull(g.tick(2_000, remote = false, current = 6, max = max))
        assertNull(g.tick(2_200, remote = false, current = 6, max = max))
    }

    @Test
    fun followsTheListenersOwnChangesOutsideAHandOver() {
        val g = ConnectVolumeGuard()
        g.tick(0, remote = false, current = 6, max = max)
        assertNull(g.tick(10_000, remote = false, current = 15, max = max)) // the listener turned it up themselves
        g.tick(11_000, remote = true, current = 15, max = max)
        assertNull(g.tick(12_000, remote = false, current = 15, max = max)) // own level is 15: nothing to undo
    }

    @Test
    fun stopsGuardingAfterTheWindow() {
        val g = ConnectVolumeGuard(windowMs = 4_000)
        g.tick(0, remote = false, current = 6, max = max)
        g.tick(1_000, remote = true, current = 6, max = max)
        g.tick(2_000, remote = false, current = 6, max = max)
        assertNull(g.tick(7_000, remote = false, current = 15, max = max)) // five seconds on: the listener's own choice
    }

    @Test
    fun rememberstheLevelOfAPhoneThatStartedAsARemote() {
        val g = ConnectVolumeGuard()
        assertNull(g.tick(0, remote = true, current = 4, max = max))
        assertNull(g.tick(1_000, remote = false, current = 4, max = max))
        assertEquals(4, g.tick(1_200, remote = false, current = 15, max = max))
    }
}
