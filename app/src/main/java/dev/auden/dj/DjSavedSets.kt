package dev.auden.dj

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DJ's sessions: the sets the listener started, and the ones they chose to keep.
 *
 * A set is an intention the DJ plays out, not a list of songs, so what is kept is the intention — what it was
 * for and how to start it again — and playing it again makes a new version of it, from what the library holds
 * then. A set that was only started is remembered for a while ([RECENT_MAX]); one that is saved is kept until
 * it is let go ([SAVED_MAX]).
 */
object DjSavedSets {
    private const val PREFS = "auden_dj_sets"
    private const val KEY = "entries"
    private const val RECENT_MAX = 12
    private const val SAVED_MAX = 60

    @Serializable
    data class Entry(
        /** The card it came from, so a saved set is recognised on the page. */
        val cardId: String,
        val kind: DjCardKind,
        val subject: String? = null,
        val number: Int? = null,
        val mood: DjMood? = null,
        /** What it was, in the words of the language it was started in. */
        val title: String,
        val reason: String? = null,
        val artist: String? = null,
        val decade: Int? = null,
        val genre: String? = null,
        val favorites: Boolean = false,
        val fresh: Boolean = false,
        val rediscover: Boolean = false,
        val album: String? = null,
        val playlist: String? = null,
        val discover: Boolean = false,
        val discoverSeed: String? = null,
        val discoverFar: Boolean = false,
        /** A few covers of what it was made of, for the list of sessions to show. */
        val covers: List<String> = emptyList(),
        val startedAt: Long = 0L,
        val saved: Boolean = false,
        val text: String = "",
        /** A set typed in words has no card: this is what was typed. */
        val typed: Boolean = false,
    ) {
        /** The request that starts it again; null for a set the DJ chose itself ([DjCardKind.FOR_YOU]). */
        fun request(): DjRequest? = if (kind == DjCardKind.FOR_YOU && !typed) null else DjRequest(
            text = text.ifBlank { title }, artist = artist, decade = decade, genre = genre, mood = mood, favorites = favorites,
            fresh = fresh, rediscover = rediscover, album = album, playlist = playlist, discover = discover,
            discoverSeed = discoverSeed, discoverFar = discoverFar,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first: what the listener has started and kept. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        _entries.value = runCatching { json.decodeFromString<List<Entry>>(raw) }.getOrDefault(emptyList())
    }

    /** A set was started: it goes to the top of the list, and keeps being saved if it was. */
    @Synchronized
    fun logStart(context: Context, entry: Entry) {
        load(context)
        val kept = _entries.value.firstOrNull { it.sameSet(entry) }
        val now = entry.copy(startedAt = System.currentTimeMillis(), saved = kept?.saved == true)
        persist(context, listOf(now) + _entries.value.filterNot { it.sameSet(entry) })
    }

    /** Keeps a set, or lets it go (it then stays among the recent ones until they push it out). */
    @Synchronized
    fun setSaved(context: Context, entry: Entry, saved: Boolean) {
        load(context)
        val existing = _entries.value.firstOrNull { it.sameSet(entry) }
        val updated = (existing ?: entry.copy(startedAt = System.currentTimeMillis())).copy(saved = saved)
        persist(context, listOf(updated) + _entries.value.filterNot { it.sameSet(entry) })
    }

    fun isSaved(entry: Entry): Boolean = _entries.value.any { it.saved && it.sameSet(entry) }

    private fun Entry.sameSet(other: Entry) = cardId == other.cardId && artist == other.artist && album == other.album && text == other.text

    private fun persist(context: Context, all: List<Entry>) {
        val saved = all.filter { it.saved }.take(SAVED_MAX)
        val recent = all.filter { !it.saved }.take(RECENT_MAX)
        val kept = (saved + recent).sortedByDescending { it.startedAt }
        _entries.value = kept
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(kept)).apply()
    }
}
