package com.music.bitchord

import com.music.bitchord.data.listentogether.ListenTogether
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherAvatarTest {
    @Test
    fun shortUrlIsUntouched() {
        val url = "https://lh3.googleusercontent.com/a/abc=s96-c"
        assertEquals(url, ListenTogether.fitAvatarUrl(url))
    }

    @Test
    fun googleSuffixIsShortenedWhenThatMakesItFit() {
        val base = "https://lh3.googleusercontent.com/a/" + "x".repeat(50)
        val url = "$base=s88-c-k-c0x00ffffff-no-rj-mo-and-more-suffix-here"
        assertEquals("$base=s96-c", ListenTogether.fitAvatarUrl(url))
    }

    @Test
    fun urlThatNeverFitsIsLeftOut() {
        assertNull(ListenTogether.fitAvatarUrl("https://example.com/" + "a".repeat(200)))
        assertNull(ListenTogether.fitAvatarUrl("https://lh3.googleusercontent.com/a/" + "x".repeat(200) + "=s96-c"))
    }
}
