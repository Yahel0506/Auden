package com.music.bitchord

import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.bitchord.data.connect.ClientMessage
import com.music.bitchord.data.connect.Command
import com.music.bitchord.data.connect.CommandAction
import com.music.bitchord.data.connect.ConnectDevice
import com.music.bitchord.data.connect.ConnectLocal
import com.music.bitchord.data.connect.ConnectManager
import com.music.bitchord.data.connect.ConnectRepeat
import com.music.bitchord.data.connect.ConnectShuffleOrder
import com.music.bitchord.data.connect.ConnectStatus
import com.music.bitchord.data.connect.ConnectTrack
import com.music.bitchord.data.connect.ConnectTransport
import com.music.bitchord.data.connect.ConnectTransportFactory
import com.music.bitchord.data.connect.ConnectTransportListener
import com.music.bitchord.data.connect.DeviceEntry
import com.music.bitchord.data.connect.MirrorPlan
import com.music.bitchord.data.connect.PlaybackState
import com.music.bitchord.data.connect.QueueEdit
import com.music.bitchord.data.connect.RemoteSession
import com.music.bitchord.data.connect.ServerMessage
import com.music.bitchord.data.connect.afterCommand
import com.music.bitchord.playback.ConnectSpeaker
import com.music.bitchord.playback.Effect
import com.music.bitchord.playback.MirrorGate
import com.music.bitchord.playback.MirrorHydrator
import com.music.bitchord.playback.MirrorRouting
import com.music.bitchord.playback.MirrorTarget
import com.music.bitchord.playback.cast.CastSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror mode: the phone showing another device's session as its own, silent. Everything that can be proved without
 * a device: the manager's mirror flag and its exits, the optimistic edits, the shuffle order, the hydration of the
 * idle player's queue, the routing of every control and queue edit, the speaker's numbers and the side-effect gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectMirrorTest {

    // ---- Rig ---------------------------------------------------------------------------------------------

    private class FakeTransport(val listener: ConnectTransportListener) : ConnectTransport {
        val sent = mutableListOf<ClientMessage>()
        var open = true
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(message: ClientMessage): Boolean {
            if (!open) return false
            sent += message
            return true
        }
    }

    private class FakeLocal : ConnectLocal {
        var snap: PlaybackState? = null
        var party = false
        val takeovers = mutableListOf<Pair<PlaybackState, Boolean>>()
        var pauses = 0
        val commands = mutableListOf<Command>()
        override fun snapshot() = snap
        override fun isPartyQueue() = party
        override fun volume() = 0.7
        override fun takeover(state: PlaybackState, play: Boolean, done: () -> Unit) {
            takeovers += state to play
            done()
        }
        override fun pause() { pauses++ }
        override fun applyCommand(command: Command) { commands += command }
    }

    private class Rig(testScope: TestScope) {
        val local = FakeLocal()
        lateinit var transport: FakeTransport
        var clock = 1_000L
        val manager = ConnectManager(
            scope = CoroutineScope(testScope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScope.testScheduler)),
            factory = ConnectTransportFactory { _, _, listener -> FakeTransport(listener).also { transport = it } },
            nowMs = { clock },
        )
        val sent get() = transport.sent
        fun join(welcome: ServerMessage.Welcome = welcome(active = "web1", state = remoteState())) {
            manager.start("a".repeat(32), ConnectDevice("me", "Pixel 8", "android"), local)
            transport.listener.onStatus(ConnectStatus.ONLINE)
            transport.listener.onMessage(welcome)
            sent.clear()
        }
        fun hear(m: ServerMessage) = transport.listener.onMessage(m)
    }

    private companion object {
        fun t(id: String) = ConnectTrack(id.padEnd(11, '_'), "T$id", "X", durationSec = 200)
        fun ids(vararg id: String) = id.map { it.padEnd(11, '_') }
        fun queueOf(vararg id: String) = id.map(::t)
        fun remoteState(
            isPlaying: Boolean = true,
            index: Int = 1,
            positionMs: Long = 4_000,
            queue: List<ConnectTrack> = queueOf("a", "b", "c", "d"),
            shuffle: Boolean = false,
            order: List<String>? = null,
            repeat: ConnectRepeat = ConnectRepeat.OFF,
        ) = PlaybackState(queue, index, positionMs, isPlaying, 0.4, shuffle, repeat, order)

        val devices = listOf(
            DeviceEntry("me", "Pixel 8", "android", true),
            DeviceEntry("web1", "Chrome on Linux", "web", true),
        )

        fun welcome(active: String? = null, state: PlaybackState? = null) =
            ServerMessage.Welcome(serverMs = 10_000, devices = devices, activeDeviceId = active, state = state, stateServerMs = 9_000)

        fun playingHere(id: String = "a") =
            PlaybackState(queueOf(id, "b"), 0, 12_000, true, 0.7, false, ConnectRepeat.OFF)
    }

    // ---- The manager's mirror flag -------------------------------------------------------------------------

    @Test
    fun mirroring_isOnWhileAnotherDeviceIsActive_andOffWhenThisOneIs() = runTest {
        val r = Rig(this)
        r.join()
        assertTrue(r.manager.mirroring.value)
        assertTrue(r.manager.isMirroring)
        r.hear(ServerMessage.Takeover(11_000, remoteState(), true))
        assertFalse(r.manager.mirroring.value)
        r.local.snap = playingHere()
        r.hear(ServerMessage.Release(12_000, "web1"))
        assertTrue("released: the phone becomes a mirror of what it was playing", r.manager.mirroring.value)
        assertEquals(1, r.local.pauses)
    }

    @Test
    fun mirroring_endsWhenTheSessionEndsOrConnectStops() = runTest {
        val r = Rig(this)
        r.join()
        r.hear(ServerMessage.Devices(12_000, devices, null))
        assertFalse(r.manager.mirroring.value)
        val r2 = Rig(this)
        r2.join()
        r2.manager.stop()
        assertFalse(r2.manager.mirroring.value)
    }

    @Test
    fun leavingTheMirror_keepsTheOtherDevicePlaying_andStaysOutUntilTheActiveDeviceChanges() = runTest {
        val r = Rig(this)
        r.join()
        r.manager.leaveMirror()
        assertFalse(r.manager.mirroring.value)
        // The other device keeps publishing: the phone does not fall back into mirroring.
        r.hear(ServerMessage.State(13_000, "web1", remoteState(index = 2)))
        assertFalse(r.manager.mirroring.value)
        assertEquals(2, r.manager.remote.value?.state?.index)
        // A different active device is a new session to mirror.
        r.hear(ServerMessage.Devices(14_000, devices, "tv"))
        assertTrue(r.manager.mirroring.value)
    }

    @Test
    fun startingSomethingHere_leavesTheMirror_andClaimsTheSession() = runTest {
        val r = Rig(this)
        r.join()
        assertTrue(r.manager.mirroring.value)
        r.manager.leaveMirror() // what the session player does on a new queue
        r.local.snap = playingHere("n")
        r.manager.onLocalIntent()
        advanceTimeBy(ConnectManager.INTENT_SETTLE_MS + 1)
        val claim = r.sent.single() as ClientMessage.Claim
        assertEquals(ids("n")[0], claim.state.current?.videoId)
        assertEquals("me", r.manager.activeDeviceId.value)
        assertFalse(r.manager.mirroring.value)
        assertNull(r.manager.remote.value)
        // A later release makes the phone a mirror again: the exit was not permanent.
        r.hear(ServerMessage.Release(15_000, "web1"))
        assertTrue(r.manager.mirroring.value)
    }

    @Test
    fun leavingAMirror_thatIsNotOne_doesNothing() = runTest {
        val r = Rig(this)
        r.join(welcome())
        r.manager.leaveMirror()
        r.hear(ServerMessage.Devices(12_000, devices, "web1"))
        // Leaving when not mirroring must not pre-arm an exit for the next session.
        r.hear(ServerMessage.State(12_500, "web1", remoteState()))
        assertTrue(r.manager.mirroring.value)
    }

    // ---- Optimistic edits ---------------------------------------------------------------------------------

    private fun PlaybackState.titles() = queue.map { it.title.removePrefix("T").trimEnd('_') }

    @Test
    fun everyControl_isSentOnce_andShownAtOnce() = runTest {
        val r = Rig(this)
        r.join()
        fun lastWire() = (r.sent.last() as ClientMessage.Remote).command
        fun state() = r.manager.remote.value!!.state

        assertTrue(r.manager.remoteCommand(Command.of(CommandAction.NEXT)))
        assertEquals(CommandAction.NEXT, lastWire().action)
        assertEquals(2, state().index)
        r.manager.remoteCommand(Command.jump(0))
        assertEquals(0, state().index)
        r.manager.remoteCommand(Command.jump(9))
        assertEquals("a jump past the queue changes nothing", 0, state().index)
        r.manager.remoteCommand(Command.of(CommandAction.NEXT))
        r.clock += 10_000
        r.manager.remoteCommand(Command.of(CommandAction.PREVIOUS))
        assertEquals("back after the first seconds restarts the song", 1, state().index)
        assertEquals(0L, state().positionMs)
        r.manager.remoteCommand(Command.of(CommandAction.PREVIOUS))
        assertEquals("and from its start goes to the previous song", 0, state().index)
        assertEquals(6, r.sent.size)
    }

    @Test
    fun queueEdits_updateTheMirroredQueueAtOnce() = runTest {
        val r = Rig(this)
        r.join()
        fun state() = r.manager.remote.value!!.state

        r.manager.remoteCommand(Command.playNext(listOf(t("x"))))
        assertEquals(listOf("a", "b", "x", "c", "d"), state().titles())
        assertEquals(1, state().index)
        r.manager.remoteCommand(Command.addToQueue(listOf(t("y"), t("z"))))
        assertEquals(listOf("a", "b", "x", "c", "d", "y", "z"), state().titles())
        r.manager.remoteCommand(Command.remove(2))
        assertEquals(listOf("a", "b", "c", "d", "y", "z"), state().titles())
        r.manager.remoteCommand(Command.move(5, 2))
        assertEquals(listOf("a", "b", "z", "c", "d", "y"), state().titles())
        r.manager.remoteCommand(Command.remove(0))
        assertEquals("removing a played song moves the playing index with it", 0, state().index)
        assertEquals("b", state().current?.title?.removePrefix("T")?.trimEnd('_'))
        r.manager.remoteCommand(Command.clearUpcoming())
        assertEquals(listOf("b"), state().titles())
        assertEquals(6, r.sent.size)
    }

    @Test
    fun movingThePlayingSong_carriesTheIndex() {
        val s = remoteState(index = 1)
        assertEquals(3, s.afterCommand(Command.move(1, 3), 0).index)
        assertEquals("b", s.afterCommand(Command.move(1, 3), 0).current?.title?.removePrefix("T")?.trimEnd('_'))
        assertEquals(2, s.afterCommand(Command.move(3, 0), 0).index)
        assertEquals(0, s.afterCommand(Command.move(0, 2), 0).index)
        assertEquals(1, s.afterCommand(Command.move(0, 0), 0).index)
    }

    @Test
    fun aQueueEditLeavesThePlayheadsClockAlone_aSeekRestartsIt() = runTest {
        val r = Rig(this)
        r.join()
        val at = r.manager.remote.value!!.atServerMs
        r.manager.remoteCommand(Command.playNext(listOf(t("x"))))
        assertEquals(at, r.manager.remote.value!!.atServerMs)
        r.clock = 3_000
        r.manager.remoteCommand(Command.seek(50_000))
        assertEquals(r.manager.serverNowMs(), r.manager.remote.value!!.atServerMs)
        assertEquals(50_000L, r.manager.remote.value!!.state.positionMs)
        // A new song starts from its beginning on a new clock, too (next, previous and jump all do).
        r.clock = 6_000
        r.manager.remoteCommand(Command.jump(2))
        assertEquals(r.manager.serverNowMs(), r.manager.remote.value!!.atServerMs)
        assertEquals(0L, r.manager.remote.value!!.state.positionMs)
        r.clock = 7_000
        r.manager.remoteCommand(Command.of(CommandAction.NEXT))
        assertEquals(r.manager.serverNowMs(), r.manager.remote.value!!.atServerMs)
        r.clock = 8_000
        r.manager.remoteCommand(Command.of(CommandAction.PREVIOUS))
        assertEquals(r.manager.serverNowMs(), r.manager.remote.value!!.atServerMs)
    }

    @Test
    fun controlsAreNotSent_whenThePhoneIsTheActiveDevice() = runTest {
        val r = Rig(this)
        r.join()
        r.hear(ServerMessage.Takeover(11_000, remoteState(), true))
        r.sent.clear()
        assertFalse(r.manager.remoteCommand(Command.playNext(listOf(t("x")))))
        assertTrue(r.sent.isEmpty())
    }

    // ---- Shuffle order over the wire ---------------------------------------------------------------------

    @Test
    fun theOrder_isKeptWhileShuffling_andDroppedWhenShuffleIsOff() = runTest {
        val r = Rig(this)
        val order = ids("a", "b", "c", "d")
        r.join(welcome("web1", remoteState(shuffle = true, order = order)))
        assertEquals(order, r.manager.remote.value!!.state.order)
        r.hear(ServerMessage.State(12_000, "web1", remoteState(shuffle = false, order = order)))
        assertNull("shuffle off: the order does not travel", r.manager.remote.value!!.state.order)
        r.hear(ServerMessage.Takeover(13_000, remoteState(shuffle = false, order = order), true))
        assertNull(r.local.takeovers.last().first.order)
    }

    @Test
    fun aTakeover_carriesTheOrderToThePlayer() = runTest {
        val r = Rig(this)
        r.join()
        val order = ids("d", "c", "b", "a")
        r.hear(ServerMessage.Takeover(13_000, remoteState(shuffle = true, order = order), true))
        assertEquals(order, r.local.takeovers.single().first.order)
    }

    @Test
    fun theActiveDevice_publishesTheOrder_withinTheDebounce() = runTest {
        val r = Rig(this)
        r.join(welcome())
        r.local.snap = PlaybackState(queueOf("a", "b", "c"), 0, 0, true, 0.7, true, ConnectRepeat.OFF, ids("c", "a", "b"))
        r.manager.onLocalIntent()
        advanceTimeBy(ConnectManager.INTENT_SETTLE_MS + 1)
        assertEquals(ids("c", "a", "b"), (r.sent.single() as ClientMessage.Claim).state.order)
        r.sent.clear()
        // A queue edit or a shuffle toggle on the phone is one debounced state.
        r.local.snap = r.local.snap!!.copy(queue = queueOf("a", "b", "c", "z"), order = ids("c", "a", "b", "z"))
        r.manager.onLocalChanged()
        r.manager.onLocalChanged()
        advanceTimeBy(ConnectManager.PUBLISH_DEBOUNCE_MS - 1)
        assertTrue(r.sent.isEmpty())
        advanceTimeBy(2)
        val state = (r.sent.single() as ClientMessage.State).state
        assertEquals(ids("c", "a", "b", "z"), state.order)
        assertEquals(4, state.queue.size)
    }

    @Test
    fun theActiveDevice_appliesQueueEditsFromRemotes_andRepublishes() = runTest {
        val r = Rig(this)
        r.join(welcome())
        r.local.snap = playingHere()
        r.manager.onLocalIntent()
        advanceTimeBy(ConnectManager.INTENT_SETTLE_MS + 1)
        r.sent.clear()
        val edits = listOf(
            Command.playNext(listOf(t("x"))), Command.addToQueue(listOf(t("y"))), Command.remove(1),
            Command.move(0, 1), Command.clearUpcoming(),
        )
        edits.forEachIndexed { i, c -> r.hear(ServerMessage.Remote(20_000L + i, "web1", c)) }
        assertEquals(edits, r.local.commands)
        advanceTimeBy(ConnectManager.PUBLISH_DEBOUNCE_MS + 1)
        assertEquals(1, r.sent.count { it is ClientMessage.State })
    }

    // ---- Pure parts ----------------------------------------------------------------------------------------

    @Test
    fun diff_isNullForTheSameQueue_andReplacesOnlyWhatDiffers() {
        val cur = ids("a", "b", "c", "d")
        assertNull(MirrorPlan.diff(cur, queueOf("a", "b", "c", "d")))
        assertEquals(QueueEdit(2, 2, queueOf("x")), MirrorPlan.diff(cur, queueOf("a", "b", "x", "c", "d")))
        assertEquals(QueueEdit(1, 2, emptyList()), MirrorPlan.diff(cur, queueOf("a", "c", "d")))
        assertEquals(QueueEdit(4, 4, queueOf("e", "f")), MirrorPlan.diff(cur, queueOf("a", "b", "c", "d", "e", "f")))
        assertEquals(QueueEdit(0, 1, emptyList()), MirrorPlan.diff(cur, queueOf("b", "c", "d")))
        assertEquals(QueueEdit(0, 4, queueOf("p", "q")), MirrorPlan.diff(cur, queueOf("p", "q")))
        assertEquals(QueueEdit(1, 3, queueOf("c", "b")), MirrorPlan.diff(cur, queueOf("a", "c", "b", "d")))
        assertEquals(QueueEdit(0, 0, queueOf("a")), MirrorPlan.diff(emptyList(), queueOf("a")))
    }

    @Test
    fun needsSeek_onlyWhenTheStateNamesAnotherRow() {
        assertTrue(MirrorPlan.needsSeek(0, remoteState(index = 2)))
        assertFalse(MirrorPlan.needsSeek(2, remoteState(index = 2)))
        assertFalse("nothing loaded remotely: nothing to seek to", MirrorPlan.needsSeek(2, remoteState(index = -1)))
    }

    @Test
    fun shuffleOrder_publish_followsTheRememberedKeys_andKeepsLateAdditionsAtTheEnd() {
        val rows = listOf("e" to "E", "b" to "B", "d" to "D", "a" to "A", "z" to "Z").map { ConnectShuffleOrder.Row("${it.first}_CONTEXT", it.second) }
        val original = listOf("a", "b", "c", "d", "e").map { "${it}_CONTEXT" }
        // "c" left the queue; "Z" was queued after the shuffle; the window holds all but "A".
        val order = ConnectShuffleOrder.publish(original, rows, listOf("E", "B", "D", "Z"))
        assertEquals(listOf("B", "D", "E", "Z"), order)
        assertNull(ConnectShuffleOrder.publish(emptyList(), rows, listOf("E")))
    }

    @Test
    fun shuffleOrder_ranks_isAPermutationThatPlacesEachSongInTheUnshuffledOrder() {
        val queue = listOf("c", "a", "d", "b")
        val ranks = ConnectShuffleOrder.ranks(queue, listOf("a", "b", "c", "d"))
        assertEquals(listOf(2, 0, 3, 1), ranks.toList())
        // Unnamed songs go after the named ones, in queue order; duplicates take their own slot.
        val partial = ConnectShuffleOrder.ranks(listOf("x", "a", "a", "y"), listOf("a", "a"))
        assertEquals(listOf(2, 0, 1, 3), partial.toList())
        assertEquals(setOf(0, 1, 2, 3), partial.toSet())
    }

    // ---- Hydration of the idle player ---------------------------------------------------------------------

    private class FakeTarget(var ids: MutableList<String> = mutableListOf(), var index: Int = -1) : MirrorTarget {
        val log = mutableListOf<String>()
        var repeatMode = ConnectRepeat.OFF
        var shuffle: Pair<Boolean, List<String>?> = false to null
        var onReplace: () -> Unit = {}
        override fun queueIds() = ids.toList()
        override fun currentIndex() = index
        override suspend fun replace(edit: QueueEdit) {
            log += "replace ${edit.from}-${edit.to} +${edit.insert.size}"
            repeat(edit.to - edit.from) { ids.removeAt(edit.from) }
            ids.addAll(edit.from, edit.insert.map { it.videoId })
            if (index !in ids.indices) index = if (ids.isEmpty()) -1 else 0
            onReplace()
        }
        override fun clear() { log += "clear"; ids.clear(); index = -1 }
        override fun seekTo(index: Int, positionMs: Long) { log += "seek $index@$positionMs"; this.index = index }
        override fun setRepeat(repeat: ConnectRepeat) { repeatMode = repeat }
        override fun setShuffle(on: Boolean, order: List<String>?) { shuffle = on to order }
    }

    @Test
    fun hydration_buildsTheQueue_andMovesToTheSessionsSong() = runTest {
        val target = FakeTarget()
        val h = MirrorHydrator(target) { true }
        val s = remoteState(index = 2, shuffle = true, order = ids("d", "c", "b", "a"), repeat = ConnectRepeat.ALL)
        h.apply(RemoteSession(s, 9_000), 5_000)
        assertEquals(ids("a", "b", "c", "d"), target.ids)
        assertEquals(listOf("replace 0-0 +4", "seek 2@5000"), target.log)
        assertEquals(ConnectRepeat.ALL, target.repeatMode)
        assertEquals(true to ids("d", "c", "b", "a"), target.shuffle)
        assertEquals(5_000L, h.lastHeadMs)
    }

    @Test
    fun hydration_ofAHeartbeat_writesNothingToTheQueue() = runTest {
        val target = FakeTarget(ids("a", "b", "c", "d").toMutableList(), index = 1)
        val h = MirrorHydrator(target) { true }
        h.apply(RemoteSession(remoteState(index = 1), 9_000), 6_000)
        assertTrue("same queue, same song: the idle player is not touched", target.log.isEmpty())
    }

    @Test
    fun hydration_ofAnEdit_isTheSmallestReplacement_thenFollowsTheSong() = runTest {
        val target = FakeTarget(ids("a", "b", "c", "d").toMutableList(), index = 1)
        val h = MirrorHydrator(target) { true }
        h.apply(RemoteSession(remoteState(queue = queueOf("a", "b", "x", "c", "d"), index = 1), 9_000), 0)
        assertEquals(listOf("replace 2-2 +1"), target.log)
        target.log.clear()
        // The playing row is removed under the player: the session says which row plays now.
        h.apply(RemoteSession(remoteState(queue = queueOf("a", "x", "c", "d"), index = 1), 9_000), 0)
        assertEquals(listOf("replace 1-2 +0"), target.log)
        target.log.clear()
        h.apply(RemoteSession(remoteState(queue = queueOf("a", "x", "c", "d"), index = 3), 9_000), 1_234)
        assertEquals(listOf("seek 3@1234"), target.log)
    }

    @Test
    fun hydration_ofAnEmptySession_clearsTheQueueOnce() = runTest {
        val target = FakeTarget(ids("a").toMutableList(), index = 0)
        val h = MirrorHydrator(target) { true }
        val empty = remoteState(queue = emptyList(), index = -1)
        h.apply(RemoteSession(empty, 9_000), 0)
        h.apply(RemoteSession(empty, 9_000), 0)
        assertEquals(listOf("clear"), target.log)
    }

    @Test
    fun hydration_stopsWritingTheMomentTheMirrorEnds() = runTest {
        var mirroring = true
        val target = FakeTarget()
        target.onReplace = { mirroring = false } // the user started something while the rows were being built
        val h = MirrorHydrator(target) { mirroring }
        h.apply(RemoteSession(remoteState(index = 2), 9_000), 0)
        assertEquals("no seek, repeat or shuffle after the exit", listOf("replace 0-0 +4"), target.log)
        assertEquals(false to null, target.shuffle)
        target.log.clear()
        h.apply(RemoteSession(remoteState(index = 2), 9_000), 0)
        assertTrue("and nothing at all while not mirroring", target.log.isEmpty())
    }

    // ---- Routing: every control and queue edit becomes a command -----------------------------------------

    private fun routing(r: Rig) = MirrorRouting(r.manager)
    private fun Rig.wire() = sent.filterIsInstance<ClientMessage.Remote>().map { it.command }

    @Test
    fun routing_sendsEveryTransportControl() = runTest {
        val r = Rig(this)
        r.join()
        val m = routing(r)
        assertTrue(m.active)
        m.play(); m.pause(); m.next(); m.previous(); m.jump(3); m.seekTo(42_000)
        m.shuffle(true); m.repeat(Player.REPEAT_MODE_ONE); m.repeat(Player.REPEAT_MODE_ALL); m.repeat(Player.REPEAT_MODE_OFF)
        assertEquals(
            listOf(
                Command.of(CommandAction.PLAY), Command.of(CommandAction.PAUSE), Command.of(CommandAction.NEXT),
                Command.of(CommandAction.PREVIOUS), Command.jump(3), Command.seek(42_000), Command.shuffle(true),
                Command.repeat(ConnectRepeat.ONE), Command.repeat(ConnectRepeat.ALL), Command.repeat(ConnectRepeat.OFF),
            ),
            r.wire(),
        )
    }

    @Test
    fun routing_seeksRelativeToTheRemotePlayhead_notTheIdlePlayers() = runTest {
        val r = Rig(this)
        r.join() // playing, 4 s at server 9_000; the local clock reads 1_000 against a server 10_000
        r.clock = 3_000 // server 12_000: the song is at 7 s
        val m = routing(r)
        m.seekBack(5_000)
        m.seekForward(15_000)
        // Each control also moves the mirrored session at once, so the second starts from where the first left it.
        assertEquals(listOf(Command.seek(2_000), Command.seek(17_000)), r.wire())
        r.sent.clear()
        m.seekBack(60_000)
        assertEquals("never before the start", listOf(Command.seek(0)), r.wire())
        r.sent.clear()
        r.clock = 190_000 // the song is at 187 s of its 200
        m.seekForward(30_000)
        assertEquals("never past the end", listOf(Command.seek(200_000)), r.wire())
    }

    @Test
    fun routing_queueEdits_areTheRightCommands() = runTest {
        val r = Rig(this)
        r.join()
        val m = routing(r)
        val x = listOf(t("x"))
        m.add(index = 2, tracks = x, currentIndex = 1) // right behind the playing song
        m.add(index = 3, tracks = x, currentIndex = 1) // further back
        m.add(index = 4, tracks = x, currentIndex = 1)
        m.add(index = 2, tracks = emptyList(), currentIndex = 1) // nothing shareable: nothing sent
        m.remove(from = 0, to = 1, currentIndex = 1, count = 4)
        m.remove(from = 2, to = 4, currentIndex = 1, count = 4) // everything after the playing song
        m.remove(from = 2, to = 3, currentIndex = 1, count = 4) // one row
        m.remove(from = 1, to = 3, currentIndex = 0, count = 4) // two rows, not the whole tail
        m.move(2, 0)
        m.move(1, 1) // nothing moves
        assertEquals(
            listOf(
                Command.playNext(x), Command.addToQueue(x), Command.addToQueue(x),
                Command.remove(0),
                Command.clearUpcoming(),
                Command.remove(2),
                Command.remove(2), Command.remove(1),
                Command.move(2, 0),
            ),
            r.wire(),
        )
    }

    @Test
    fun routing_isInactiveOnceTheMirrorEnds_andANewSessionEndsIt() = runTest {
        val r = Rig(this)
        r.join()
        val m = routing(r)
        m.newSession()
        assertFalse(m.active)
    }

    // ---- The speaker the session player reports from -----------------------------------------------------

    @Test
    fun speaker_reportsTheRemoteSession_asTheSessionPlayersClock() = runTest {
        val r = Rig(this)
        r.join()
        val s = ConnectSpeaker(r.manager)
        r.clock = 3_000 // server 12_000: 4 s + 3 s
        assertTrue(s.active)
        assertTrue(s.playWhenReady)
        assertTrue(s.isPlaying)
        assertEquals(Player.STATE_READY, s.playbackState)
        assertEquals(7_000L, s.positionMs)
        assertEquals(200_000L, s.durationMs)
        assertEquals(40, s.deviceVolume)

        r.hear(ServerMessage.State(12_000, "web1", remoteState(isPlaying = false, positionMs = 9_000)))
        assertFalse(s.isPlaying)
        assertFalse(s.playWhenReady)
        r.clock = 60_000
        assertEquals("paused: the playhead holds", 9_000L, s.positionMs)
    }

    @Test
    fun speaker_isSilentAndBlankWhenThePhoneIsNotMirroring() = runTest {
        val r = Rig(this)
        r.join()
        r.hear(ServerMessage.Takeover(11_000, remoteState(), true))
        val s = ConnectSpeaker(r.manager)
        assertFalse(s.active)
        assertFalse(s.isPlaying)
        assertEquals(Player.STATE_IDLE, s.playbackState)
        assertEquals(0L, s.positionMs)
    }

    @Test
    fun speaker_withoutADuration_saysItIsUnknown() = runTest {
        val r = Rig(this)
        r.join(welcome("web1", remoteState(queue = listOf(ConnectTrack("aaaaaaaaaaa", "A", "X")), index = 0)))
        assertEquals(C.TIME_UNSET, ConnectSpeaker(r.manager).durationMs)
    }

    @Test
    fun speaker_sendsPlayPauseSeekAndVolumeToTheActiveDevice() = runTest {
        val r = Rig(this)
        r.join()
        val s = ConnectSpeaker(r.manager)
        s.play(); s.pause(); s.seekTo(30_000); s.setVolume(0.25f); s.setVolume(7f)
        assertEquals(
            listOf(Command.of(CommandAction.PLAY), Command.of(CommandAction.PAUSE), Command.seek(30_000), Command.volume(0.25), Command.volume(1.0)),
            r.wire(),
        )
    }

    private class RecordingSink : CastSink {
        val events = mutableListOf<String>()
        override fun onRemotePlaybackChanged() { events += "playback" }
        override fun onRemoteRouteChanged() { events += "route" }
        override fun onRemoteVolumeChanged() { events += "volume" }
    }

    @Test
    fun speaker_tellsTheSessionPlayerWhatToReRead() = runTest {
        val r = Rig(this)
        r.join()
        val s = ConnectSpeaker(r.manager)
        val sink = RecordingSink().also { s.sink = it }
        val a = RemoteSession(remoteState(), 9_000)
        val b = RemoteSession(remoteState(positionMs = 8_000), 12_000)
        s.announce(null, a, wasActive = false, isActive = true)
        assertEquals(listOf("route", "playback", "volume"), sink.events)
        sink.events.clear()
        s.announce(a, b, wasActive = true, isActive = true)
        assertEquals("a new position re-anchors; the volume is unchanged", listOf("playback"), sink.events)
        sink.events.clear()
        s.announce(b, RemoteSession(b.state.copy(volume = 0.9), 13_000), wasActive = true, isActive = true)
        assertEquals(listOf("playback", "volume"), sink.events)
        sink.events.clear()
        s.announce(b, null, wasActive = true, isActive = false)
        assertEquals(listOf("route"), sink.events)
    }

    @Test
    fun speaker_aStartOfTheIdlePlayer_isReportedOnlyWhileMirroring() = runTest {
        val r = Rig(this)
        r.join()
        val s = ConnectSpeaker(r.manager)
        var started = 0
        s.onLocalStarted = { started++ }
        s.onLocalStartedPlaying(stubPlayer())
        assertEquals(1, started)
        r.hear(ServerMessage.Takeover(11_000, remoteState(), true))
        s.onLocalStartedPlaying(stubPlayer())
        assertEquals(1, started)
    }

    private fun stubPlayer(): Player = java.lang.reflect.Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java),
    ) { _, _, _ -> null } as Player

    // ---- The side-effect gate ----------------------------------------------------------------------------

    @Test
    fun theGate_blocksEverySideEffect_whileMirroring_andNoneOtherwise() {
        var mirroring = true
        val gate = MirrorGate { mirroring }
        for (effect in Effect.values()) assertTrue("$effect must be off while mirroring", gate.blocks(effect))
        assertTrue(gate.active)
        mirroring = false
        for (effect in Effect.values()) assertFalse("$effect must run when playing here", gate.blocks(effect))
        assertFalse(gate.active)
        for (effect in Effect.values()) assertFalse(MirrorGate.OFF.blocks(effect))
    }

    @Test
    fun theGate_followsTheManagersMirrorFlag() = runTest {
        val r = Rig(this)
        val gate = MirrorGate { r.manager.isMirroring }
        r.join()
        assertTrue(gate.blocks(Effect.LISTEN_BOOKKEEPING))
        assertTrue(gate.blocks(Effect.QUEUE_GROWTH))
        r.manager.leaveMirror()
        assertFalse(gate.blocks(Effect.LISTEN_BOOKKEEPING))
        assertFalse(gate.blocks(Effect.STREAM_FETCH))
    }
}
