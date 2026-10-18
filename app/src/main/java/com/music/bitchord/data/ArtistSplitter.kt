package com.music.bitchord.data

import java.util.Locale

/**
 * Splits a credit like "Artist A & Artist B, Artist C" into the artists it
 * names, so each of them gets their own entry in the Artists tab and a
 * collaboration shows up under everyone on it.
 *
 * What counts as a separator is a list rather than a rule because no rule is
 * right: "&" joins two artists on most records and is part of the name on
 * "Simon & Garfunkel". The [keepWhole] list is the escape hatch for the second
 * case — names listed there are never taken apart.
 */
object ArtistSplitter {
    val DEFAULT_SEPARATORS = listOf("&", ",", ";", "//", " ft. ", " feat. ", " featuring ", " x ")

    val DEFAULT_KEEP_WHOLE = listOf(
        "Simon & Garfunkel", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young",
        "Tyler, The Creator", "Hall & Oates", "Florence & The Machine", "Brooks & Dunn",
        "Ike & Tina Turner", "Sly & The Family Stone", "Mumford & Sons", "Of Monsters and Men",
    )

    fun split(
        credit: String,
        separators: List<String> = DEFAULT_SEPARATORS,
        keepWhole: List<String> = DEFAULT_KEEP_WHOLE,
    ): List<String> {
        val text = credit.trim()
        if (text.isEmpty()) return emptyList()
        // Names that must survive are swapped for a placeholder, split around, and swapped back.
        val held = mutableListOf<String>()
        var work = text
        for (name in keepWhole) {
            val at = work.indexOf(name, ignoreCase = true)
            if (at >= 0) {
                held += work.substring(at, at + name.length)
                work = work.replaceRange(at, at + name.length, "\u0000${held.lastIndex}\u0000")
            }
        }
        val pattern = Regex(separators.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return work.split(pattern)
            .map { part -> Regex("\u0000(\\d+)\u0000").replace(part) { held[it.groupValues[1].toInt()] }.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }
}
