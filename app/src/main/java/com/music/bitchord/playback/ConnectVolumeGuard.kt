package com.music.bitchord.playback

/**
 * Keeps this phone's media volume its own across Auden Connect hand-overs.
 *
 * The volume is individual to each device: playing on the web at full volume must never raise the phone's.
 * While the phone mirrors another device (or just after it stops mirroring or becomes the active device) the
 * level of the other device is what the player UI shows, and a change of route can leave the music stream at
 * maximum. This remembers the phone's own level whenever it is plainly its own, and puts it back if the stream
 * jumps to maximum in the few seconds after a hand-over.
 *
 * Pure: [tick] says what the stream should be set to, if anything; the caller reads and writes the stream.
 */
class ConnectVolumeGuard(private val windowMs: Long = WINDOW_MS) {
    private var own: Int? = null
    private var wasRemote = false
    private var guardUntilMs = 0L

    /**
     * @param remote this phone mirrors, or is released to, another device
     * @param current the music stream's index now
     * @param max the music stream's top index
     * @return the index to put the stream back to, or null to leave it alone
     */
    fun tick(nowMs: Long, remote: Boolean, current: Int, max: Int): Int? {
        val handingOver = wasRemote && !remote
        wasRemote = remote
        if (handingOver) guardUntilMs = nowMs + windowMs
        val inWindow = nowMs < guardUntilMs
        if (!remote && !inWindow) {
            own = current
            guardUntilMs = 0L
            return null
        }
        // Started while another device was playing: the stream is still the phone's own at this point.
        val kept = own ?: current.also { own = it }
        // Only the symptom is undone: the stream jumped to the top while the phone's own level was lower.
        return if (inWindow && max > 0 && current >= max && kept < max) kept else null
    }

    companion object {
        /** How long after a hand-over a jump to maximum is undone. */
        const val WINDOW_MS = 4_000L
    }
}
