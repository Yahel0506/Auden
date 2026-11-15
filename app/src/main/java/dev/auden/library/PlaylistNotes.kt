package dev.auden.library

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The line or two a listener writes about a playlist kept on this device — what it is for, who it is for. Kept
 * beside the playlist by its id, since it is the listener's own and a sync of the original has no word about it.
 */
object PlaylistNotes {
    private const val PREFS = "auden_playlist_notes"
    private lateinit var prefs: SharedPreferences
    private val _notes = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Every note, by playlist id (without the `local:playlist:` prefix). */
    val notes: StateFlow<Map<String, String>> = _notes.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _notes.value = prefs.all.mapNotNull { (k, v) -> (v as? String)?.takeIf(String::isNotBlank)?.let { k to it } }.toMap()
    }

    private fun key(id: String) = id.removePrefix("local:playlist:").removePrefix("local:offline:")

    fun get(id: String): String = _notes.value[key(id)].orEmpty()

    fun set(id: String, text: String) {
        val clean = text.trim()
        val k = key(id)
        _notes.value = if (clean.isEmpty()) _notes.value - k else _notes.value + (k to clean)
        if (this::prefs.isInitialized) prefs.edit().apply { if (clean.isEmpty()) remove(k) else putString(k, clean) }.apply()
    }
}
