package com.music.bitchord.data

import org.junit.Test
import org.junit.Assert.assertEquals

class AppUpdateCheckerTest {
    private val raw = "https://raw.githubusercontent.com/Yahel0506/Auden"

    @Test
    fun changelogUrls_prefersTheAppLanguageThenEnglish() {
        assertEquals(
            listOf("$raw/v1.12.0/changelog/1.12.0/es.md", "$raw/v1.12.0/changelog/1.12.0/en.md"),
            AppUpdateChecker.changelogUrls("v1.12.0", "1.12.0", "es"),
        )
    }

    @Test
    fun changelogUrls_englishAloneIsNotRepeated() {
        assertEquals(
            listOf("$raw/v1.12.0/changelog/1.12.0/en.md"),
            AppUpdateChecker.changelogUrls("v1.12.0", "1.12.0", "en"),
        )
    }

    @Test
    fun changelogUrls_aBetaReadsItsVersionsFolder() {
        assertEquals(
            listOf("$raw/v1.12.0-beta2/changelog/1.12.0/en.md"),
            AppUpdateChecker.changelogUrls("v1.12.0-beta2", "1.12.0-beta2", ""),
        )
    }
}
