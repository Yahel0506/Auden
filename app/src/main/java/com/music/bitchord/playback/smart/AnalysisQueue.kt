package com.music.bitchord.playback.smart

/**
 * The analyzer's one worker thread, with a queue that picks its next task by who is wanted now
 * rather than by who asked first.
 *
 * Analysis of a track takes the better part of a minute, and a listener skipping through a queue
 * asks for several in a row. In arrival order the track being listened to waits behind every one
 * already left behind; here, [rank] is asked afresh each time the worker is free, so the track
 * playing and the one after it go first and anything nobody wants any more is taken out of the way
 * (it comes up first, and its task finds itself cancelled and returns at once).
 *
 * [rank] gets a task's track id, or null for a task that belongs to no track, and answers the
 * lowest number for the most urgent. Equal ranks keep their order of arrival.
 */
internal class AnalysisQueue(name: String, private val rank: (String?) -> Int) {

    private class Task(val trackId: String?, val run: () -> Unit, val seq: Long)

    private val lock = Object()
    private val tasks = ArrayList<Task>()
    private var arrivals = 0L
    private var closed = false

    private val worker = Thread(::work, name).apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
        start()
    }

    fun execute(trackId: String?, run: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            tasks += Task(trackId, run, arrivals++)
            lock.notifyAll()
        }
    }

    fun shutdownNow() {
        synchronized(lock) {
            closed = true
            tasks.clear()
            lock.notifyAll()
        }
        worker.interrupt()
    }

    private fun work() {
        while (true) {
            val task = take() ?: return
            // A task that throws must not take the only worker with it.
            try {
                task.run()
            } catch (_: Throwable) {
            }
        }
    }

    private fun take(): Task? {
        synchronized(lock) {
            while (true) {
                if (closed) return null
                if (tasks.isNotEmpty()) {
                    val ranked = tasks.map { it to rank(it.trackId) }
                    val best = ranked.minWith(compareBy({ it.second }, { it.first.seq })).first
                    tasks.remove(best)
                    return best
                }
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
    }
}
