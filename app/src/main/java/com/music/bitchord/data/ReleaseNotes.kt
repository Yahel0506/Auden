package com.music.bitchord.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.BuildConfig
import java.util.Locale

/**
 * The changelog of the version that is installed, for the "What's new" section in Settings.
 *
 * The `changelog/` folder is packed into the app as assets at build time (see
 * `app/build.gradle.kts`), so the notes are the ones shipped with this build and
 * need no network. Each version is a folder of one Markdown file per language,
 * and a language without its own file reads the English one.
 */
object ReleaseNotes {

    /** The notes for the version running now, in the app's language, or null when there are none. */
    fun forInstalledVersion(context: Context): String? =
        forVersion(context, BuildConfig.VERSION_NAME.substringBefore('-'), appLanguage())

    /** The notes for [version] in [language], falling back to English. Null when neither exists. */
    fun forVersion(context: Context, version: String, language: String): String? =
        candidates(version, language).firstNotNullOfOrNull { path ->
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }

    /** The asset paths tried in order: the app's language, then English. */
    internal fun candidates(version: String, language: String): List<String> {
        val code = language.lowercase(Locale.ROOT).ifBlank { "en" }
        return listOf("$version/$code.md", "$version/en.md").distinct()
    }

    private fun appLanguage(): String {
        val chosen = AppCompatDelegate.getApplicationLocales()
        return (if (chosen.isEmpty) Locale.getDefault() else chosen[0])?.language.orEmpty()
    }
}
