package dev.auden.library

/**
 * "Refresh from source" for one library copy, worked out without touching anything: what the copy becomes, what
 * the snapshot becomes, and the counts a preview shows (+N / −M). Pure; [dev.auden.transfer.PlaylistRefresher]
 * reads the source and writes the result.
 *
 * Three lists go in, each in the origin's id space (YouTube Music video ids; Spotify track ids):
 * - the copy as it is now ([Local], in its order),
 * - the source as it is now ([Remote], in its order),
 * - the base: the snapshot taken at the last import or refresh — what the copy took in from the source then —
 *   or null when none was kept.
 *
 * **[Mode.MERGE]** (D1, the default), one rule per case:
 *
 * | in base | in copy | in source | result |
 * |---|---|---|---|
 * | – | – | yes | **added** (the source gained it) |
 * | yes | yes | – | **removed** (the source dropped it; it came from there, so the listener did not add it) |
 * | yes | – | yes | **stays removed** (the listener took it out; it never comes back) |
 * | – | yes | – | **kept** (the listener's own addition, never in a snapshot) |
 * | yes | yes | yes / – | – | unchanged / gone everywhere |
 *
 * A song of the copy with no id at the origin ([Local.key] null: a Spotify copy's song the listener added from
 * anywhere else) is the listener's and is always kept. A song is also still "in the source" when the source lists
 * the same playable song under another id (two Spotify tracks matched to one video): it is never taken out then.
 *
 * **Null base** (a copy made before snapshots were kept, or from a link that named no ids): nothing is removed and
 * nothing counts as "taken out by the listener" — the first refresh only adds, and then records the snapshot.
 *
 * **Order.** The copy's own order wins for everything it keeps. A song the source gained goes right after the
 * closest song before it, in the source's order, that is in the result; with none before it, at the top. So songs
 * appended at the source's end land after its last song, songs inserted in its middle land there, and runs of new
 * songs keep the source's order.
 *
 * **[Mode.REPLACE]**: the copy becomes the source, in the source's order; the listener's additions and removals
 * are dropped. A song already in the copy keeps its entry (and what the device knows about it).
 *
 * **Duplicates.** The copy's own duplicates are left alone (only the source's removals take songs out, every
 * entry of the song). The source's are taken once — by id, and by playable song — the way copies have always
 * been kept.
 *
 * **Unmatched** ([Remote.song] null: a Spotify track with no YouTube Music match) are never added; they are
 * returned in [Result.unmatched] to be reported, and left out of the new snapshot, so that once a match is found a
 * later refresh adds them instead of taking them for songs the listener removed.
 *
 * The new snapshot is the source's ids that the copy now accounts for (present, added, or kept out on purpose),
 * in the source's order, each once.
 */
object PlaylistMerge {
    enum class Mode { MERGE, REPLACE }

    /** A song of the copy: [key] its id at the origin (null: it has none), [song] its playable identity (video id). */
    data class Local(val key: String?, val song: String)

    /** An entry of the source's listing: [key] its id there, [song] the playable song it is, null when none matched. */
    data class Remote(val key: String, val song: String?)

    /** Where each entry of the result comes from: the copy's entry at [index], or the source's. */
    sealed interface Pick {
        val index: Int
        data class FromLocal(override val index: Int) : Pick
        data class FromRemote(override val index: Int) : Pick
    }

    /**
     * [added] new from the source; [removed] entries of the copy taken out; [keptLocal] entries the listener added
     * that stay; [unchanged] entries in both; [stayedRemoved] songs the source still has that the listener took out
     * and that stay out (MERGE only); [unmatched] songs of the source that could not be added.
     */
    data class Counts(
        val added: Int = 0,
        val removed: Int = 0,
        val keptLocal: Int = 0,
        val unchanged: Int = 0,
        val stayedRemoved: Int = 0,
        val unmatched: Int = 0,
    )

    /**
     * [entries] the copy after the refresh, in order; [snapshot] the base for the next one; [unmatched] indexes into
     * the source's listing; [changed] whether [entries] differ from the copy at all (content or order).
     */
    data class Result(
        val entries: List<Pick>,
        val snapshot: List<String>,
        val counts: Counts,
        val unmatched: List<Int>,
        val changed: Boolean,
    )

    fun merge(local: List<Local>, remote: List<Remote>, base: List<String>?, mode: Mode): Result = when (mode) {
        Mode.MERGE -> threeWay(local, remote, base?.toHashSet())
        Mode.REPLACE -> replace(local, remote)
    }

    private fun threeWay(local: List<Local>, remote: List<Remote>, base: Set<String>?): Result {
        val remoteKeys = remote.mapTo(HashSet()) { it.key }
        val remoteSongs = remote.mapNotNullTo(HashSet()) { it.song }

        // The copy, in its order, less what the source dropped.
        val kept = ArrayList<Int>(local.size)
        var removed = 0
        var keptLocal = 0
        var unchanged = 0
        local.forEachIndexed { i, entry ->
            val key = entry.key
            when {
                key != null && key in remoteKeys || entry.song in remoteSongs -> {
                    kept += i
                    unchanged++
                }
                key != null && base != null && key in base -> removed++
                else -> {
                    kept += i
                    keptLocal++
                }
            }
        }

        // Where each kept song stands, by id and by song: the anchors new songs are placed after.
        val atKey = HashMap<String, Int>()
        val atSong = HashMap<String, Int>()
        kept.forEachIndexed { position, i ->
            local[i].key?.let { atKey.putIfAbsent(it, position) }
            atSong.putIfAbsent(local[i].song, position)
        }

        val after = HashMap<Int, MutableList<Int>>() // kept position (-1: the top) to the source's entries placed after it
        val addedKeys = HashSet<String>()
        val addedSongs = HashSet<String>()
        val unmatched = ArrayList<Int>()
        val snapshot = LinkedHashSet<String>()
        var anchor = -1
        var added = 0
        var stayedRemoved = 0
        remote.forEachIndexed { j, entry ->
            val position = atKey[entry.key] ?: entry.song?.let { atSong[it] }
            when {
                position != null -> {
                    anchor = position
                    snapshot += entry.key
                }
                // The source lists it twice (or two of its ids are one song): taken once.
                entry.key in addedKeys || entry.song != null && entry.song in addedSongs -> snapshot += entry.key
                base != null && entry.key in base -> {
                    if (snapshot.add(entry.key)) stayedRemoved++
                }
                entry.song == null -> if (entry.key !in snapshot) unmatched += j
                else -> {
                    after.getOrPut(anchor) { ArrayList() } += j
                    addedKeys += entry.key
                    addedSongs += entry.song
                    snapshot += entry.key
                    added++
                }
            }
        }

        val entries = ArrayList<Pick>(kept.size + added)
        after[-1]?.forEach { entries += Pick.FromRemote(it) }
        kept.forEachIndexed { position, i ->
            entries += Pick.FromLocal(i)
            after[position]?.forEach { entries += Pick.FromRemote(it) }
        }
        val unmatchedKeys = unmatched.mapTo(HashSet()) { remote[it].key }
        return Result(
            entries = entries,
            snapshot = snapshot.toList(),
            counts = Counts(
                added = added,
                removed = removed,
                keptLocal = keptLocal,
                unchanged = unchanged,
                stayedRemoved = stayedRemoved,
                unmatched = unmatchedKeys.size,
            ),
            unmatched = unmatched.distinctBy { remote[it].key },
            changed = added > 0 || removed > 0,
        )
    }

    private fun replace(local: List<Local>, remote: List<Remote>): Result {
        val byKey = HashMap<String, Int>()
        val bySong = HashMap<String, Int>()
        local.forEachIndexed { i, entry ->
            entry.key?.let { byKey.putIfAbsent(it, i) }
            bySong.putIfAbsent(entry.song, i)
        }
        val used = HashSet<Int>()
        val songsIn = HashSet<String>()
        val snapshot = LinkedHashSet<String>()
        val entries = ArrayList<Pick>()
        val unmatched = ArrayList<Int>()
        var added = 0
        remote.forEachIndexed { j, entry ->
            if (entry.key in snapshot) return@forEachIndexed
            val mine = byKey[entry.key] ?: entry.song?.let { bySong[it] }
            when {
                mine != null && mine !in used -> {
                    entries += Pick.FromLocal(mine)
                    used += mine
                    songsIn += local[mine].song
                    entry.song?.let(songsIn::add)
                    snapshot += entry.key
                }
                mine != null -> snapshot += entry.key
                entry.song == null -> if (unmatched.none { remote[it].key == entry.key }) unmatched += j
                entry.song in songsIn -> snapshot += entry.key
                else -> {
                    entries += Pick.FromRemote(j)
                    songsIn += entry.song
                    snapshot += entry.key
                    added++
                }
            }
        }
        val identity = entries.size == local.size && entries.withIndex().all { (i, pick) -> pick is Pick.FromLocal && pick.index == i }
        return Result(
            entries = entries,
            snapshot = snapshot.toList(),
            counts = Counts(
                added = added,
                removed = local.size - used.size,
                unchanged = used.size,
                unmatched = unmatched.size,
            ),
            unmatched = unmatched,
            changed = !identity,
        )
    }

    /**
     * How the copy differs from what it took in at the last import or refresh — the listener's own edits, for a
     * status line. [added] entries the source never gave it; [removed] songs it was given that are no longer there.
     * Null when there is no base to compare with.
     */
    data class LocalChanges(val added: Int, val removed: Int) {
        val any: Boolean get() = added > 0 || removed > 0
    }

    fun localChanges(local: List<Local>, base: List<String>?): LocalChanges? {
        base ?: return null
        val baseSet = base.toHashSet()
        val have = local.mapNotNullTo(HashSet()) { it.key }
        return LocalChanges(
            added = local.count { it.key == null || it.key !in baseSet },
            removed = baseSet.count { it !in have },
        )
    }
}
