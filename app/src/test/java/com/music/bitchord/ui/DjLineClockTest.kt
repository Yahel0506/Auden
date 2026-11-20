package com.music.bitchord.ui

import com.music.bitchord.ui.screens.DjLineClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjLineClockTest {
    private val words = listOf(3, 2)

    @Test
    fun aLineComesInAWordAtATimeThenHoldsThenLeaves() {
        assertEquals(0, DjLineClock.at(words, 0).wordsIn)
        assertEquals(2, DjLineClock.at(words, DjLineClock.WORD_MS * 2 + 5).wordsIn)
        val held = DjLineClock.at(words, DjLineClock.WORD_MS * 3 + 100)
        assertEquals(3, held.wordsIn)
        assertFalse(held.leaving)
        assertTrue(DjLineClock.at(words, DjLineClock.WORD_MS * 3 + DjLineClock.HOLD_MS + 10).leaving)
    }

    @Test
    fun theNextLineFollowsAndTheCycleWrapsAround() {
        val first = 3 * DjLineClock.WORD_MS + DjLineClock.HOLD_MS + DjLineClock.LEAVE_MS
        val second = 2 * DjLineClock.WORD_MS + DjLineClock.HOLD_MS + DjLineClock.LEAVE_MS
        assertEquals(1, DjLineClock.at(words, first + 1).line)
        assertEquals(0, DjLineClock.at(words, first + second + 1).line)
    }

    @Test
    fun theSameMomentGivesTheSamePlaceHoweverOftenItIsAskedAfterTheBannerCameBack() {
        val a = DjLineClock.at(words, 5_000)
        val b = DjLineClock.at(words, 5_000)
        assertEquals(a.line, b.line)
        assertEquals(a.wordsIn, b.wordsIn)
        assertEquals(a.leaving, b.leaving)
    }
}
