package com.music.bitchord.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericFeedShareTest {
    @Test
    fun aNewListenerGetsTheWholeGenericFeed() {
        assertEquals(1.0, genericFeedShare(0), 0.0)
        assertEquals(1.0, genericFeedShare(4), 0.0)
    }

    @Test
    fun theFirstSongsPlayedAlreadyTakeSomeOfItAway() {
        assertTrue(genericFeedShare(10) < 1.0)
        assertTrue(genericFeedShare(30) < genericFeedShare(10))
    }

    @Test
    fun onceTheListeningIsTheirsNoneOfItIsLeft() {
        assertEquals(0.0, genericFeedShare(60), 0.0)
        assertEquals(0.0, genericFeedShare(5_000), 0.0)
    }
}
