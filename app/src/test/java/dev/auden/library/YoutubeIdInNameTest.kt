package dev.auden.library

import dev.auden.library.ident.YoutubeIdInName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoutubeIdInNameTest {
    @Test
    fun readsATrailingBracketedId() {
        assertEquals("dQw4w9WgXcQ", YoutubeIdInName.extract("Title [dQw4w9WgXcQ].m4a"))
        assertEquals("-tJYN-eG1zk", YoutubeIdInName.extract("Artist - Title [-tJYN-eG1zk].opus"))
    }

    @Test
    fun readsAnUrlForm() {
        assertEquals("dQw4w9WgXcQ", YoutubeIdInName.extract("clip v=dQw4w9WgXcQ.mp3"))
    }

    @Test
    fun ignoresWhatIsNotAnId() {
        listOf(
            "Title [dQw4w9WgXc].m4a", "Title [dQw4w9WgXcQQ].m4a", "Title [dQw4w9WgXcQ] (Live).m4a",
            "Title (dQw4w9WgXcQ).m4a", "dQw4w9WgXcQ.m4a", "Title [dQw4w9WgXcR].m4a",
        ).forEach { assertNull(it, YoutubeIdInName.extract(it)) }
    }

    @Test
    fun ignoresWordsAndCounters() {
        listOf(
            "Butterflies", "Bonus_Track", "Hidden_Song", "theme_music", "Love_Me_Now", "TITLE_THEME",
            "Track_01_04", "REC00010004", "VID20240104", "My_Song_004", "07_Sunrises", "12345678904",
        ).forEach { assertNull(it, YoutubeIdInName.extract("Artist - Title [$it].mp4")) }
    }
}
