package com.music.bitchord

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service's own side effects and the session player's controls cannot be run on the JVM, so this reads
 * PlaybackService.kt and proves each place that has to answer to mirror mode still does: every gated function opens
 * with its [com.music.bitchord.playback.Effect] check, and every control of the session player is routed (or ends
 * the mirror) before it reaches the idle ExoPlayer. Deleting one of those lines fails here, by name.
 */
class ConnectMirrorWiringTest {

    private val source: String = listOf(
        "src/main/java/com/music/bitchord/playback/PlaybackService.kt",
        "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt",
    ).map(::File).first { it.exists() }.readText()

    private val sessionPlayer: String = source.substring(source.indexOf("private class SessionPlayer("))

    /** Every body that starts at `fun name(` in [text], cut to its first [span] characters. */
    private fun bodies(text: String, signature: Regex, span: Int = 420): List<String> =
        signature.findAll(text).map {
            val start = it.range.first
            val limit = minOf(text.length, start + span)
            // Never into the next function: a neighbour's check must not satisfy this one's.
            val next = text.indexOf("\n        override fun ", start + 10).takeIf { n -> n in start until limit } ?: limit
            text.substring(start, next)
        }.toList()

    private fun assertGated(name: String, token: String, text: String = source, span: Int = 420) {
        val found = bodies(text, Regex("""fun $name\("""), span)
        assertTrue("$name not found", found.isNotEmpty())
        found.forEach { assertTrue("$name must check $token near its start:\n$it", it.contains(token)) }
    }

    @Test
    fun serviceSideEffects_askTheGate() {
        assertGated("loadAutoplayForCurrentTrack", "mirrorGate.blocks(Effect.QUEUE_GROWTH)", span = 160)
        assertGated("refreshAutoplayIfQueueEmpty", "mirrorGate.blocks(Effect.QUEUE_GROWTH)", span = 160)
        assertGated("scheduleBoundedContextTopUp", "mirrorGate.blocks(Effect.QUEUE_GROWTH)", span = 160)
        assertGated("driveDirectWindow", "mirrorGate.blocks(Effect.STREAM_FETCH)", span = 260)
        assertGated("onIsPlayingChanged", "mirrorGate.blocks(Effect.LISTEN_BOOKKEEPING)", span = 360)
        assertGated("onMediaItemTransition", "mirrorGate.blocks(Effect.TRACK_START)", span = 2400)
        assertGated("onTimelineChanged", "mirrorGate.blocks(Effect.QUEUE_SNAPSHOT)", span = 1600)
        assertGated("onRepeatModeChanged", "mirrorGate.blocks(Effect.QUEUE_GROWTH)", span = 700)
        assertGated("reportProgress", "mirrorGate.blocks(Effect.LISTEN_BOOKKEEPING)", span = 1200)
        for (f in listOf("revertCurrentToOriginal", "swapCurrentToVersion", "upgradeQualityNow")) {
            assertGated(f, "mirrorGate.blocks(Effect.STREAM_FETCH)", span = 260)
        }
    }

    @Test
    fun sessionPlayerControls_areRoutedToTheMirror() {
        val routed = listOf(
            "play", "pause", "stop", "setPlayWhenReady", "seekTo", "seekBack", "seekForward", "seekToPrevious",
            "seekToDefaultPosition", "seekToPreviousMediaItem", "seekToNextMediaItem", "seekToNext",
            "setMediaItems", "addMediaItems", "removeMediaItem", "removeMediaItems", "moveMediaItem",
            "moveMediaItems", "replaceMediaItems", "clearMediaItems", "prepare", "setRepeatMode",
        )
        for (name in routed) {
            val found = bodies(sessionPlayer, Regex("""override fun $name\("""), span = 330)
            assertTrue("$name is not overridden by the session player", found.isNotEmpty())
            found.forEach { assertTrue("$name must answer to mirror mode first:\n$it", it.contains("mirror.")) }
        }
    }

    @Test
    fun theMirrorsQueue_isNeverPrepared_andNewQueuesEndIt() {
        // A new queue ends the mirror before it is written; the idle queue is never prepared.
        for (name in listOf("setMediaItems", "clearMediaItems")) {
            bodies(sessionPlayer, Regex("""override fun $name\("""), span = 330).forEach {
                assertTrue("$name must end the mirror:\n$it", it.contains("mirror.newSession()"))
            }
        }
        assertTrue(bodies(sessionPlayer, Regex("""override fun prepare\(\)"""), span = 120).single().contains("if (mirror.active) return"))
        for (action in listOf("ACTION_START_BOUNDED_CONTEXT", "ACTION_START_STATION", "ACTION_BEGIN_RADIO_QUEUE")) {
            val at = source.indexOf("$action ->")
            assertTrue("$action not handled", at > 0)
            assertTrue("$action must end the mirror", source.substring(at, at + 140).contains("mirrorDoor.newSession()"))
        }
        assertTrue(source.contains("mirrorDoor.shuffle(!QueueShuffle.enabled.value)"))
    }

    @Test
    fun mirrorSpeaker_isOneOfTheServicesSpeakers() {
        assertTrue(source.contains("Speakers(listOf(connectSpeaker, castPlayback, watchSpeaker))"))
    }
}
