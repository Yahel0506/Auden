package com.music.bitchord

import com.music.bitchord.data.ArtistSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistSplitterTest {
    @Test
    fun splitsOnTheUsualSeparators() {
        assertEquals(listOf("A", "B", "C"), ArtistSplitter.split("A & B, C"))
        assertEquals(listOf("A", "B"), ArtistSplitter.split("A ft. B"))
        assertEquals(listOf("A", "B"), ArtistSplitter.split("A FEAT. B"))
        assertEquals(listOf("A", "B"), ArtistSplitter.split("A; B"))
        assertEquals(listOf("A", "B"), ArtistSplitter.split("A // B"))
    }

    @Test
    fun keepsNamesThatContainASeparator() {
        assertEquals(listOf("Simon & Garfunkel"), ArtistSplitter.split("Simon & Garfunkel"))
        assertEquals(listOf("Earth, Wind & Fire", "Someone"), ArtistSplitter.split("Earth, Wind & Fire, Someone"))
        assertEquals(listOf("AC/DC"), ArtistSplitter.split("AC/DC"))
    }

    @Test
    fun dropsEmptiesAndDuplicates() {
        assertEquals(listOf("A", "B"), ArtistSplitter.split("A, , B, a"))
        assertEquals(emptyList<String>(), ArtistSplitter.split("   "))
    }
}
