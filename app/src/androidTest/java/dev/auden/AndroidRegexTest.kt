package dev.auden

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.ArtistSplitter
import com.music.bitchord.download.DownloadLayout
import dev.auden.dj.DjRequestParser
import dev.auden.dj.DjSpeech
import dev.auden.dj.DjTrack
import dev.auden.dj.DjWriter
import dev.auden.library.ident.YoutubeIdInName
import dev.auden.library.io.M3u
import dev.auden.library.io.Paths
import dev.auden.library.io.PlaylistEntry
import dev.auden.library.io.PlaylistMatcher
import dev.auden.library.io.Xspf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's regex engine (ICU) is stricter than the JVM's that the unit tests
 * run on: an unescaped `}` compiles on one and throws on the other, which is how
 * the folder-structure row once crashed Settings. Everything that builds a
 * regular expression is exercised here, on a real runtime.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRegexTest {
    @Test
    fun downloadLayoutBuildsNames() {
        assertEquals("Band/Album/03 - Song.flac", DownloadLayout.relativeName(
            DownloadLayout.DEFAULT_TEMPLATE,
            DownloadLayout.Info(title = "Song", artist = "Band", album = "Album", track = 3), "flac",
        ))
        DownloadLayout.PRESETS.forEach { DownloadLayout.preview(it) }
    }

    @Test
    fun youtubeIdsAreReadFromNames() {
        assertEquals("dQw4w9WgXcQ", YoutubeIdInName.extract("Title [dQw4w9WgXcQ].m4a"))
        assertEquals(null, YoutubeIdInName.extract("Butterflies [Butterflies].mp4"))
    }

    @Test
    fun artistsAreSplit() {
        assertEquals(listOf("A", "B"), ArtistSplitter.split("A & B"))
        assertEquals(listOf("Simon & Garfunkel"), ArtistSplitter.split("Simon & Garfunkel"))
    }

    @Test
    fun playlistFilesRoundTrip() {
        val entries = listOf(PlaylistEntry(path = "/m/a.mp3", title = "T", artist = "A", durationSec = 10.0))
        assertEquals("/m/a.mp3", M3u.parse(M3u.write("x", entries, null), null).entries.single().path)
        assertEquals("/m/a.mp3", Xspf.parse(Xspf.write("x", entries, null), null).entries.single().path)
        assertEquals("cafe deja vu", PlaylistMatcher.normalize("Café Déjà Vu (Remastered)"))
        Paths.safeFileName("a:b/c")
    }

    @Test
    fun theDjReadsRequestsAndShapesSpeech() {
        val pool = listOf(DjTrack("1", "t", "Ana", genre = "Rock", year = 1995))
        assertEquals(1990, DjRequestParser.parse("algo tranquilo de los 90 de Ana", pool).decade)
        assertEquals("Ana", DjRequestParser.parse("algo de Ana", pool).artist)
        assertEquals(3, DjSpeech.shape("Hola. Esto es una prueba: dos frases.", 1).size)
        val writer = DjWriter(object : dev.auden.dj.DjPhrases { override fun templates(key: String) = emptyList<String>() }, "es", 1)
        assertEquals("De 1999.", writer.fill("De {year}.", mapOf("year" to "1999")))
    }
}
