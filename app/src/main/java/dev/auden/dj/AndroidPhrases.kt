package dev.auden.dj

import android.content.Context
import android.content.res.Configuration
import com.music.bitchord.R
import java.util.Locale

/**
 * The DJ's lines, from the app's resources in the language the DJ speaks.
 *
 * A language is spoken only when its own translation exists (see [DjPhrasesCheck]): reading English
 * lines in a Portuguese voice would be worse than saying nothing.
 */
class AndroidPhrases(context: Context, language: String) : DjPhrases {
    private val resources = context.applicationContext.let { app ->
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale(language)) }
        app.createConfigurationContext(config).resources
    }

    private val ids: Map<String, Int> = mapOf(
        "greeting_morning" to R.array.dj_greeting_morning,
        "greeting_afternoon" to R.array.dj_greeting_afternoon,
        "greeting_evening" to R.array.dj_greeting_evening,
        "greeting_night" to R.array.dj_greeting_night,
        "relaxed" to R.array.dj_relaxed,
        "unrecognized" to R.array.dj_unrecognized,
        "intro_artist_recent" to R.array.dj_intro_artist_recent,
        "intro_artist_always" to R.array.dj_intro_artist_always,
        "intro_artist_saved" to R.array.dj_intro_artist_saved,
        "intro_favorites" to R.array.dj_intro_favorites,
        "intro_rediscover" to R.array.dj_intro_rediscover,
        "intro_rediscover_plain" to R.array.dj_intro_rediscover_plain,
        "intro_fresh" to R.array.dj_intro_fresh,
        "intro_discover" to R.array.dj_intro_discover,
        "intro_discover_plain" to R.array.dj_intro_discover_plain,
        "intro_playlist" to R.array.dj_intro_playlist,
        "intro_mood_focus" to R.array.dj_intro_mood_focus,
        "intro_mood_chill" to R.array.dj_intro_mood_chill,
        "intro_mood_energy" to R.array.dj_intro_mood_energy,
        "intro_mood_acoustic" to R.array.dj_intro_mood_acoustic,
        "intro_request_match" to R.array.dj_intro_request_match,
        "intro_theme" to R.array.dj_intro_theme,
        "what_artist" to R.array.dj_what_artist,
        "what_decade" to R.array.dj_what_decade,
        "what_genre" to R.array.dj_what_genre,
        "what_album" to R.array.dj_what_album,
        "what_favorites" to R.array.dj_what_favorites,
        "what_fresh" to R.array.dj_what_fresh,
        "what_rediscover" to R.array.dj_what_rediscover,
        "mood_word_focus" to R.array.dj_mood_word_focus,
        "mood_word_chill" to R.array.dj_mood_word_chill,
        "mood_word_energy" to R.array.dj_mood_word_energy,
        "mood_word_acoustic" to R.array.dj_mood_word_acoustic,
        "ago_months" to R.array.dj_ago_months,
        "ago_year" to R.array.dj_ago_year,
        "by_artist" to R.array.dj_by_artist,
        "note_year" to R.array.dj_note_year,
        "note_album" to R.array.dj_note_album,
        "note_plain" to R.array.dj_note_plain,
        "signoff" to R.array.dj_signoff,
        "moment_morning" to R.array.dj_moment_morning,
        "moment_afternoon" to R.array.dj_moment_afternoon,
        "moment_evening" to R.array.dj_moment_evening,
        "moment_night" to R.array.dj_moment_night,
    )

    override fun templates(key: String): List<String> =
        ids[key]?.let { resources.getStringArray(it).toList() }.orEmpty()
}

/** Whether the DJ has its own lines in [language]: a translation file that declares that language. */
object DjPhrasesCheck {
    fun supports(context: Context, language: String): Boolean {
        val config = Configuration(context.applicationContext.resources.configuration).apply { setLocale(Locale(language)) }
        val declared = context.applicationContext.createConfigurationContext(config).getString(R.string.dj_phrases_language)
        return declared == language
    }
}
