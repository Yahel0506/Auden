package com.music.bitchord.data.connect

/**
 * The pure parts of mirroring another device's session: what a command does to a state before the active device
 * answers (the optimistic update), how a mirror's queue is brought to a state's, and how a shuffled queue keeps the
 * order it came from. No Android in here, so all of it is unit-tested.
 */

/** Where "back" restarts the song instead of going to the previous one (ExoPlayer's own default). */
const val RESTART_THRESHOLD_MS = 3_000L

/** What a command does to [this] before the active device has answered; its next state is the truth. */
fun PlaybackState.afterCommand(command: Command, headMs: Long): PlaybackState = when (command.action) {
    CommandAction.PLAY -> copy(isPlaying = true, positionMs = headMs)
    CommandAction.PAUSE -> copy(isPlaying = false, positionMs = headMs)
    CommandAction.TOGGLE -> copy(isPlaying = !isPlaying, positionMs = headMs)
    CommandAction.SEEK -> command.number?.let { copy(positionMs = it.toLong().coerceAtLeast(0L)) } ?: this
    CommandAction.VOLUME -> command.number?.let { copy(volume = it.coerceIn(0.0, 1.0)) } ?: this
    CommandAction.SHUFFLE -> command.flag?.let { copy(shuffle = it, order = if (it) order else null) } ?: this
    CommandAction.REPEAT -> command.repeatMode?.let { copy(repeat = it) } ?: this
    CommandAction.NEXT -> when {
        index + 1 < queue.size -> copy(index = index + 1, positionMs = 0L)
        repeat == ConnectRepeat.ALL && queue.isNotEmpty() -> copy(index = 0, positionMs = 0L)
        else -> this
    }
    CommandAction.PREVIOUS ->
        if (headMs > RESTART_THRESHOLD_MS || index <= 0) copy(positionMs = 0L) else copy(index = index - 1, positionMs = 0L)
    CommandAction.JUMP -> command.index?.takeIf { it in queue.indices }?.let { copy(index = it, positionMs = 0L) } ?: this
    CommandAction.PLAY_NEXT -> insertTracks(command.tracks, at = index + 1)
    CommandAction.ADD_TO_QUEUE -> insertTracks(command.tracks, at = queue.size)
    CommandAction.REMOVE -> command.index?.takeIf { it in queue.indices }?.let { removeAt(it) } ?: this
    CommandAction.MOVE -> {
        val from = command.index
        val to = command.to
        if (from != null && to != null && from in queue.indices && to in queue.indices) moved(from, to) else this
    }
    CommandAction.CLEAR_UPCOMING ->
        if (index in queue.indices && index + 1 < queue.size) copy(queue = queue.subList(0, index + 1)) else this
}

/** True when the command moves the playhead or the play state, so the state's clock has to be re-anchored. */
fun Command.reanchors(): Boolean = when (action) {
    CommandAction.PLAY, CommandAction.PAUSE, CommandAction.TOGGLE, CommandAction.SEEK,
    CommandAction.NEXT, CommandAction.PREVIOUS, CommandAction.JUMP -> true
    else -> false
}

private fun PlaybackState.insertTracks(tracks: List<ConnectTrack>?, at: Int): PlaybackState {
    if (tracks.isNullOrEmpty()) return this
    val where = at.coerceIn(0, queue.size)
    val newQueue = queue.subList(0, where) + tracks + queue.subList(where, queue.size)
    // Songs put before the playing one (never the case for next/add) would shift it.
    val newIndex = if (index >= where && index >= 0) index + tracks.size else index
    return copy(queue = newQueue, index = newIndex)
}

private fun PlaybackState.removeAt(at: Int): PlaybackState {
    val newQueue = queue.filterIndexed { i, _ -> i != at }
    val newIndex = when {
        newQueue.isEmpty() -> -1
        at < index -> index - 1
        else -> minOf(index, newQueue.size - 1)
    }
    return copy(queue = newQueue, index = newIndex)
}

private fun PlaybackState.moved(from: Int, to: Int): PlaybackState {
    if (from == to) return this
    val mutable = queue.toMutableList()
    mutable.add(to, mutable.removeAt(from))
    val newIndex = when {
        index == from -> to
        from < index && to >= index -> index - 1
        from > index && to <= index -> index + 1
        else -> index
    }
    return copy(queue = mutable, index = newIndex)
}

/** One replacement that turns a queue into another: items [from, to) of the old one become [insert]. */
data class QueueEdit(val from: Int, val to: Int, val insert: List<ConnectTrack>)

object MirrorPlan {
    /**
     * The smallest single replacement that makes [current] (videoIds) equal [target], or null when they already are:
     * the common head and tail stay, so the playing song, the rows on screen and what is buffered are not touched
     * by an edit somewhere else in the queue.
     */
    fun diff(current: List<String>, target: List<ConnectTrack>): QueueEdit? {
        var head = 0
        while (head < current.size && head < target.size && current[head] == target[head].videoId) head++
        if (head == current.size && head == target.size) return null
        var tail = 0
        while (tail < current.size - head && tail < target.size - head &&
            current[current.size - 1 - tail] == target[target.size - 1 - tail].videoId
        ) tail++
        return QueueEdit(head, current.size - tail, target.subList(head, target.size - tail))
    }

    /** Whether the mirror's player has to be moved to the state's song. */
    fun needsSeek(playerIndex: Int, state: PlaybackState): Boolean = state.index >= 0 && playerIndex != state.index
}

/**
 * Shuffle on the phone physically reorders the queue, so the order from before has to be carried next to it.
 * [publish] says it (as videoIds) for the window being sent, [ranks] reads it back for a queue received.
 */
object ConnectShuffleOrder {

    /** A queue row as the shuffle remembers it: its identity key and the song it holds. */
    class Row(val key: String, val videoId: String)

    /**
     * The unshuffled order of the songs in [window] (shareable videoIds in queue order): follow [originalKeys]
     * through [rows] (the whole player queue), keep the songs the window holds, and put the ones that were added
     * after the shuffle at the end. Null when nothing was remembered.
     */
    fun publish(originalKeys: List<String>, rows: List<Row>, window: List<String>): List<String>? {
        if (originalKeys.isEmpty() || window.isEmpty()) return null
        val byKey = HashMap<String, ArrayDeque<Row>>()
        rows.forEach { byKey.getOrPut(it.key) { ArrayDeque() }.addLast(it) }
        val left = HashMap<String, Int>()
        window.forEach { left.merge(it, 1, Int::plus) }
        val out = ArrayList<String>(window.size)
        for (key in originalKeys) {
            val row = byKey[key]?.removeFirstOrNull() ?: continue
            val n = left[row.videoId] ?: continue
            if (n <= 0) continue
            left[row.videoId] = n - 1
            out += row.videoId
        }
        for (id in window) {
            val n = left[id] ?: continue
            if (n > 0) {
                left[id] = n - 1
                out += id
            }
        }
        return out
    }

    /**
     * For each song of [queue], its position in the unshuffled [order]: a permutation of 0 until queue.size. A song
     * the order does not name goes after the ones it does, in queue order.
     */
    fun ranks(queue: List<String>, order: List<String>): IntArray {
        val ranks = IntArray(queue.size) { -1 }
        val slots = HashMap<String, ArrayDeque<Int>>()
        queue.forEachIndexed { i, id -> slots.getOrPut(id) { ArrayDeque() }.addLast(i) }
        var next = 0
        for (id in order) {
            val at = slots[id]?.removeFirstOrNull() ?: continue
            ranks[at] = next++
        }
        for (i in queue.indices) if (ranks[i] < 0) ranks[i] = next++
        return ranks
    }
}
