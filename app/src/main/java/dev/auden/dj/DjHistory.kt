package dev.auden.dj

import android.content.Context

/**
 * The songs the DJ has played lately, kept across sessions so that a new one does not open on the same
 * favourites as the last. The planner leans away from them; it never forbids them, because a small
 * library has to repeat sooner or later.
 */
object DjHistory {
    private const val PREFS = "auden_dj"
    private const val KEY = "recent"
    private const val MAX = 400

    fun recent(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }.takeLast(MAX).toSet()

    @Synchronized
    fun record(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() && it != id } + id
        prefs.edit().putString(KEY, ids.takeLast(MAX).joinToString("\n")).apply()
    }
}
