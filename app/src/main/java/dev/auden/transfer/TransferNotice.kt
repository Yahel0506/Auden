package dev.auden.transfer

import android.content.Context

/**
 * The one-time notice for a listener who was signed in to YouTube Music when the library became Auden's own: what
 * they used to see live on the Library page now comes in from "Transfer your music". Shown once, then never again.
 */
object TransferNotice {
    private const val PREFS = "auden_transfer"
    private const val KEY_SEEN = "library_notice_seen"

    fun pending(context: Context): Boolean =
        !context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
    }
}
