package dev.auden.dj

import android.content.Context
import android.content.res.Configuration
import com.music.bitchord.R
import java.util.Locale

/** The DJ's templates, read from the app's resources in the language the DJ speaks. */
class AndroidPhrases(context: Context, language: String) : DjPhrases {
    private val resources = context.applicationContext.let { app ->
        // Resolved for the DJ's language even when the app's own is another: the voice and the words must agree.
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
    )

    override fun templates(key: String): List<String> =
        ids[key]?.let { resources.getStringArray(it).toList() }.orEmpty()
}
