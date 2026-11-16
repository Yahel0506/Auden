package dev.auden.dj

import dev.auden.dj.voice.DjPronunciation
import dev.auden.dj.voice.DjPronunciation.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjPronunciationTest {
    private val lexicon = Lexicon.parse(
        """
        # comment
        NSQK	nescuic	ness-kwik
        AC/DC	ei si di si	ay see dee see
        Bad Bunny	bad buni
        """.trimIndent(),
    )

    @Test
    fun theDictionarySaysAKnownNameTheWayItIsMeantInEachLanguage() {
        assertEquals("Ahora suena nescuic.", DjPronunciation.prepare("Ahora suena NSQK.", "es", emptyList(), lexicon))
        assertEquals("Now playing ness-kwik.", DjPronunciation.prepare("Now playing NSQK.", "en", emptyList(), lexicon))
    }

    @Test
    fun aBlankCellLeavesTheNameAsItIs() {
        assertEquals("Now Bad Bunny.", DjPronunciation.prepare("Now Bad Bunny.", "en", emptyList(), lexicon))
        assertEquals("Ahora bad buni.", DjPronunciation.prepare("Ahora Bad Bunny.", "es", emptyList(), lexicon))
    }

    @Test
    fun namesMatchWithoutCaseOrPunctuationAndOnlyAsWholeWords() {
        assertEquals("sube ei si di si ya", DjPronunciation.prepare("sube AC/DC ya", "es", emptyList(), lexicon))
        // Only a whole word: a longer word that happens to start with the name is not the name.
        assertEquals("NSQKfans", DjPronunciation.prepare("NSQKfans", "es", emptyList(), lexicon))
        assertEquals("nescuic fans", DjPronunciation.prepare("nsqk fans", "es", emptyList(), lexicon))
    }

    @Test
    fun aNameWithNoVowelIsSpelledOutInTheVoicesLanguage() {
        assertEquals("eme ge eme te", DjPronunciation.respell("MGMT", "es"))
        assertEquals("bee tee ess", DjPronunciation.respell("BTS", "en"))
        assertEquals("be te ese", DjPronunciation.respell("BTS", "es"))
    }

    @Test
    fun theRulesApplyToTheSessionsArtistsThatTheDictionaryDoesNotKnow() {
        assertEquals("Suena be te ese", DjPronunciation.prepare("Suena BTS", "es", listOf("BTS"), Lexicon.EMPTY))
    }

    @Test
    fun symbolsStandForTheLettersTheyReplace() {
        assertEquals("suicideboys", DjPronunciation.respell("\$uicideboy\$", "en"))
        assertEquals("Pink", DjPronunciation.respell("P!nk", "en"))
    }

    @Test
    fun anOrdinaryNameIsLeftAlone() {
        assertNull(DjPronunciation.respell("Radiohead", "en"))
        assertNull(DjPronunciation.respell("Rosalía", "es"))
        assertEquals("Suena Radiohead.", DjPronunciation.prepare("Suena Radiohead.", "es", listOf("Radiohead"), lexicon))
    }

    @Test
    fun aNameInAScriptTheVoiceCannotReadWantsAnAlias() {
        assertTrue(DjPronunciation.needsAlias("방탄소년단"))
        assertTrue(DjPronunciation.needsAlias("Кино"))
        assertTrue(!DjPronunciation.needsAlias("Björk"))
        assertEquals("Suena BTS ya", DjPronunciation.prepare("Suena 방탄소년단 ya", "es", listOf("방탄소년단"), Lexicon.EMPTY) { "BTS" })
    }

    @Test
    fun foldingIgnoresCaseAccentsAndPunctuation() {
        assertEquals("acdc", DjPronunciation.fold("AC/DC"))
        assertEquals("maneskin", DjPronunciation.fold("Måneskin"))
        assertEquals("sigurros", DjPronunciation.fold("Sigur Rós"))
    }

    @Test
    fun theBundledDictionaryParsesAndSaysTheNamesItIsFor() {
        val file = java.io.File("src/main/assets/dj/pronunciations.tsv")
        val bundled = Lexicon.parse(file.readText())
        assertTrue("the dictionary is empty", bundled.size > 50)
        assertEquals("nescuic", bundled.lookup("NSQK", "es"))
        assertEquals("ei si di si", bundled.lookup("ac/dc", "es"))
        assertNull(bundled.lookup("Radiohead", "es"))
    }
}
