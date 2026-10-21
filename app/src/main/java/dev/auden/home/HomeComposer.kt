package dev.auden.home

import com.music.bitchord.data.model.HomeShelf

/** What the listener chose for Home: which local shelves, and how much of Home they are. */
data class HomeSettings(
    val enabled: Set<LocalShelfKind> = LocalShelfKind.entries.toSet(),
    /**
     * 0 keeps Home entirely YouTube Music's; 100 puts the local shelves first and as many of them
     * as there are. In between, local shelves take that share of the slots, evenly spread.
     */
    val localWeight: Int = DEFAULT_WEIGHT,
) {
    companion object {
        const val DEFAULT_WEIGHT = 45
    }
}

/**
 * Merges YouTube Music's shelves with the ones built from the device.
 *
 * The rules, in order of precedence:
 *  - Offline there is only the device: the remote shelves cannot be opened.
 *  - A disabled kind, and a shelf with nothing in it, are left out.
 *  - Otherwise the two sources alternate, in proportion to [HomeSettings.localWeight]. YouTube
 *    Music's first shelf — its recently played, or the feed's lead — keeps the top of the page
 *    unless the weight is high enough that Home is mostly the listener's own.
 *  - Home is never empty because of this: with nothing local, the remote shelves go through
 *    untouched, and with nothing remote the local ones stand alone.
 */
object HomeComposer {

    fun compose(
        remote: List<HomeShelf>,
        local: List<LocalShelf>,
        settings: HomeSettings,
        online: Boolean,
    ): List<HomeShelf> {
        val locals = local
            .filter { it.kind in settings.enabled && it.items.isNotEmpty() }
            .map { HomeShelf(title = it.title, items = it.items, subtitle = it.subtitle) }
        val remotes = if (online) remote.filter { it.items.isNotEmpty() } else emptyList()
        if (locals.isEmpty()) return remotes
        if (remotes.isEmpty()) return locals

        val weight = settings.localWeight.coerceIn(0, 100)
        if (weight == 0) return remotes
        if (weight == 100) return locals + remotes

        // Lead with the remote's first shelf unless the page is mostly local.
        val out = ArrayList<HomeShelf>(locals.size + remotes.size)
        val r = remotes.iterator()
        val l = locals.iterator()
        if (weight < LOCAL_FIRST_WEIGHT && r.hasNext()) out += r.next()
        // A running balance, so the local share is spread evenly rather than bunched.
        // Mostly-local pages open on a local shelf: the balance starts already due.
        var balance = if (weight >= LOCAL_FIRST_WEIGHT) 100 - weight else 0
        while (r.hasNext() || l.hasNext()) {
            balance += weight
            val takeLocal = l.hasNext() && (balance >= 100 || !r.hasNext())
            if (takeLocal) {
                balance -= 100
                out += l.next()
            } else if (r.hasNext()) {
                out += r.next()
            } else {
                out += l.next()
            }
        }
        return out
    }

    private const val LOCAL_FIRST_WEIGHT = 75
}
