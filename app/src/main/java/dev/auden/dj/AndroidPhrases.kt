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
        "request_ack" to R.array.dj_request_ack,
        "relaxed" to R.array.dj_relaxed,
        "unrecognized" to R.array.dj_unrecognized,
        "connector" to R.array.dj_connector,
        "intro_artist" to R.array.dj_intro_artist,
        "intro_decade" to R.array.dj_intro_decade,
        "intro_genre" to R.array.dj_intro_genre,
        "intro_mood_focus" to R.array.dj_intro_mood_focus,
        "intro_mood_chill" to R.array.dj_intro_mood_chill,
        "intro_mood_energy" to R.array.dj_intro_mood_energy,
        "intro_mood_acoustic" to R.array.dj_intro_mood_acoustic,
        "intro_album" to R.array.dj_intro_album,
        "intro_favorites" to R.array.dj_intro_favorites,
        "intro_rediscover" to R.array.dj_intro_rediscover,
        "intro_fresh" to R.array.dj_intro_fresh,
        "note_year" to R.array.dj_note_year,
        "note_album" to R.array.dj_note_album,
        "note_plain" to R.array.dj_note_plain,
        "signoff" to R.array.dj_signoff,
        "intro_discover" to R.array.dj_intro_discover,
        "motive_moment" to R.array.dj_motive_moment,
        "motive_top" to R.array.dj_motive_top,
        "moment_morning" to R.array.dj_moment_morning,
        "moment_afternoon" to R.array.dj_moment_afternoon,
        "moment_evening" to R.array.dj_moment_evening,
        "moment_night" to R.array.dj_moment_night,
        "moment_weekend" to R.array.dj_moment_weekend,
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
