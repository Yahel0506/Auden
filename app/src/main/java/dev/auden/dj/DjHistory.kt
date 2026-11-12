package dev.auden.dj

import android.content.Context

/**
 * What the DJ has played and said lately, kept across sessions so that a new one does not open on the same
 * favourites, the same song or the same kind of set as the last. The planner leans away from all of it; it
 * never forbids any of it, because a small library has to repeat sooner or later.
 *
 * Three memories, each in the order it happened: the songs played, the songs a set opened on, and the themes
 * of the sets (so "your playlist X" does not come round every session).
 */
object DjHistory {
    private const val PREFS = "auden_dj"
    private const val KEY = "recent"
    private const val OPENERS = "openers"
    private const val THEMES = "themes"
    private const val MAX = 400
    private const val MAX_OPENERS = 80
    private const val MAX_THEMES = 40

    private fun read(context: Context, key: String): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()
            .split('\n').filter { it.isNotEmpty() }

    @Synchronized
    private fun append(context: Context, key: String, value: String, max: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = read(context, key).filter { it != value } + value
        prefs.edit().putString(key, all.takeLast(max).joinToString("\n")).apply()
    }

    /** The songs played lately, as a set. */
    fun recent(context: Context): Set<String> = read(context, KEY).takeLast(MAX).toSet()

    /** The songs played lately, oldest first: how recently each was played is how hard it is leaned away from. */
    fun recentOrdered(context: Context): List<String> = read(context, KEY).takeLast(MAX)

    fun record(context: Context, id: String) = append(context, KEY, id, MAX)

    /** The songs that opened a set lately, oldest first. */
    fun openers(context: Context): List<String> = read(context, OPENERS).takeLast(MAX_OPENERS)

    fun recordOpener(context: Context, id: String) = append(context, OPENERS, id, MAX_OPENERS)

    /** The themes of the sets played lately, oldest first, as [DjTheme.key]s. */
    fun themes(context: Context): List<String> = read(context, THEMES).takeLast(MAX_THEMES)

    fun recordTheme(context: Context, key: String) {
        // Moving a theme to the end when it comes round again keeps this a list of the last distinct ones.
        append(context, THEMES, key, MAX_THEMES)
    }
}
