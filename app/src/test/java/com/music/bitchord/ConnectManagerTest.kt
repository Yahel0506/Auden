package com.music.bitchord

import com.music.bitchord.data.connect.ClientMessage
import com.music.bitchord.data.connect.Command
import com.music.bitchord.data.connect.CommandAction
import com.music.bitchord.data.connect.ConnectDevice
import com.music.bitchord.data.connect.ConnectLocal
import com.music.bitchord.data.connect.ConnectManager
import com.music.bitchord.data.connect.ConnectRepeat
import com.music.bitchord.data.connect.ConnectStatus
import com.music.bitchord.data.connect.ConnectTrack
import com.music.bitchord.data.connect.ConnectTransport
import com.music.bitchord.data.connect.ConnectTransportFactory
import com.music.bitchord.data.connect.ConnectTransportListener
import com.music.bitchord.data.connect.DeviceEntry
import com.music.bitchord.data.connect.PlaybackState
import com.music.bitchord.data.connect.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same cases as web/app/src/auden/connect/session.test.ts, against a fake socket and a fake player, plus
 * the Android-only rule: nothing but the user starting playback on this phone claims the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectManagerTest {

    private class FakeTransport(val listener: ConnectTransportListener) : ConnectTransport {
        val sent = mutableListOf<ClientMessage>()
        var open = true
        var started = false
        var stopped = false
        override fun start() { started = true }
        override fun stop() { stopped = true }
        override fun send(message: ClientMessage): Boolean {
            if (!open) return false
            sent += message
            return true
        }
    }

    private class FakeLocal : ConnectLocal {
        var snap: PlaybackState? = playing("a")
        var party = false
        var vol = 0.7
        val takeovers = mutableListOf<Pair<PlaybackState, Boolean>>()
        var pendingDone: (() -> Unit)? = null
        var autoDone = true
        var pauses = 0
        val commands = mutableListOf<Command>()
        override fun snapshot() = snap
        override fun isPartyQueue() = party
        override fun volume() = vol
        override fun takeover(state: PlaybackState, play: Boolean, done: () -> Unit) {
            takeovers += state to play
            if (autoDone) done() else pendingDone = done
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
        fun join(
            welcome: ServerMessage.Welcome = welcome(),
            online: Boolean = true,
        ) {
            manager.start("a".repeat(32), ConnectDevice("me", "Pixel 8", "android"), local)
            if (online) transport.listener.onStatus(ConnectStatus.ONLINE)
            transport.listener.onMessage(welcome)
            sent.clear()
        }
        fun hear(m: ServerMessage) = transport.listener.onMessage(m)
    }

    private companion object {
        fun track(id: String) = ConnectTrack(id.padEnd(11, '_'), "T$id", "X", durationSec = 200)
        fun playing(id: String, isPlaying: Boolean = true) =
            PlaybackState(listOf(track(id), track("b")), 0, 12_000, isPlaying, 0.7, false, ConnectRepeat.OFF)
        fun remoteState(isPlaying: Boolean = true, index: Int = 0, positionMs: Long = 4_000) =
            PlaybackState(listOf(ConnectTrack("x1xxxxxxxxx", "One", "A", durationSec = 100), ConnectTrack("x2xxxxxxxxx", "Two", "B")),
                index, positionMs, isPlaying, 0.4, true, ConnectRepeat.ALL)
        val devices = listOf(
            DeviceEntry("me", "Pixel 8", "android", true),
            DeviceEntry("web1", "Chrome on Linux", "web", true),
            DeviceEntry("tv", "Living room", "tv", false),
        )
        fun welcome(active: String? = null, state: PlaybackState? = null) =
            ServerMessage.Welcome(serverMs = 10_000, devices = devices, activeDeviceId = active, state = state, stateServerMs = 9_000)
    }

    @Test
    fun welcome_listsOnlineDevices_andShowsAnotherDeviceAsPlaying() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        assertEquals(listOf("me", "web1"), r.manager.devices.value.map { it.deviceId })
        assertEquals("web1", r.manager.activeDeviceId.value)
        assertEquals("One", r.manager.remote.value?.state?.current?.title)
        assertEquals(9_000L, r.manager.remote.value?.atServerMs)
        assertTrue(r.manager.isRemote)
        assertEquals(ConnectStatus.ONLINE, r.manager.status.value)
    }

    @Test
    fun welcome_measuresTheServerClockOffset() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        // serverMs 10_000 against a local 1_000.
        assertEquals(9_000L, r.manager.offsetMs)
        r.clock = 3_000
        assertEquals(12_000L, r.manager.serverNowMs())
        // State said at server 9_000 with 4_000 ms, playing: 3_000 ms have passed by 12_000.
        assertEquals(7_000L, r.manager.remotePlayheadMs())
    }

    @Test
    fun startingPlaybackHere_claimsTheSession_andSaysWhatItPlays() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.local.snap = playing("a")
        r.manager.onLocalIntent()
        // One gesture is several player calls: the claim waits for the finished queue.
        assertTrue(r.sent.isEmpty())
        advanceTimeBy(ConnectManager.INTENT_SETTLE_MS + 1)
        val claim = r.sent.single() as ClientMessage.Claim
        assertEquals(0, claim.state.index)
        assertTrue(claim.state.isPlaying)
        assertEquals(12_000L, claim.state.positionMs)
        assertEquals("me", r.manager.activeDeviceId.value)
        assertNull(r.manager.remote.value)
    }

    @Test
    fun aPlayerEventAlone_neverClaims_soARemoteSessionStaysUntilTheUserPressesPlay() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        // The player starts by itself (audio focus back, a restored queue): not the user.
        r.local.snap = playing("a")
        r.manager.onLocalChanged()
        advanceTimeBy(5_000)
        assertTrue(r.sent.none { it is ClientMessage.Claim })
        assertEquals("web1", r.manager.activeDeviceId.value)
    }

    @Test
    fun aUserActionThatLeavesThePlayerPaused_doesNotClaim() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.manager.onLocalIntent()
        advanceTimeBy(1_000)
        assertTrue(r.sent.none { it is ClientMessage.Claim })
    }

    @Test
    fun doesNotClaim_forAPartyQueue_aLocalFile_orWhileOffline() = runTest {
        val r = Rig(this)
        r.local.party = true
        r.join()
        r.manager.onLocalIntent()
        advanceTimeBy(1_000)
        assertTrue(r.sent.isEmpty())
        r.local.party = false
        r.local.snap = null // a local file or a DJ clip: nothing shareable
        r.manager.onLocalIntent()
        advanceTimeBy(1_000)
        assertTrue(r.sent.isEmpty())

        val offline = Rig(this)
        offline.join(online = false)
        offline.manager.onLocalIntent()
        advanceTimeBy(1_000)
        assertTrue(offline.sent.isEmpty())
    }

    @Test
    fun joiningWhileAlreadyPlayingHere_claims() = runTest {
        val r = Rig(this)
        r.join()
        // welcome(null) found the phone playing: the claim went out before sent was cleared.
        assertEquals("me", r.manager.activeDeviceId.value)
        val r2 = Rig(this)
        r2.manager.start("a".repeat(32), ConnectDevice("me", "Pixel 8", "android"), r2.local)
        r2.transport.listener.onStatus(ConnectStatus.ONLINE)
        r2.hear(welcome())
        assertTrue(r2.sent.single() is ClientMessage.Claim)
    }

    @Test
    fun takeover_loadsTheOtherDevicesQueue_andPlays() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Takeover(11_000, remoteState(index = 1, positionMs = 7_500), true))
        val (loaded, play) = r.local.takeovers.single()
        assertEquals(1, loaded.index)
        assertEquals(7_500L, loaded.positionMs)
        assertEquals(listOf("x1xxxxxxxxx", "x2xxxxxxxxx"), loaded.queue.map { it.videoId })
        assertTrue(loaded.shuffle)
        assertEquals(ConnectRepeat.ALL, loaded.repeat)
        assertEquals(0.4, loaded.volume, 0.0)
        assertTrue(play)
        assertEquals("me", r.manager.activeDeviceId.value)
        assertNull(r.manager.remote.value)
        assertFalse(r.manager.isRemote)
    }

    @Test
    fun takeover_publishesOnlyOnceTheQueueIsLoaded() = runTest {
        val r = Rig(this)
        r.local.autoDone = false
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Takeover(11_000, remoteState(), true))
        r.manager.onLocalChanged()
        advanceTimeBy(1_000)
        assertTrue("nothing is said while the queue loads", r.sent.isEmpty())
        r.local.snap = playing("x1")
        r.local.pendingDone!!.invoke()
        assertTrue(r.sent.single() is ClientMessage.State)
    }

    @Test
    fun bareTakeover_becomesActiveWithoutLoadingAnything() = runTest {
        val r = Rig(this)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Takeover(11_000, null, true))
        assertEquals("me", r.manager.activeDeviceId.value)
        assertTrue(r.local.takeovers.isEmpty())
    }

    @Test
    fun takeover_withAMalformedState_isNotApplied() = runTest {
        val r = Rig(this)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Takeover(11_000, remoteState(index = 9), true))
        assertTrue(r.local.takeovers.isEmpty())
    }

    @Test
    fun release_pausesThePlayer_andShowsTheOtherDevice() = runTest {
        val r = Rig(this)
        r.join()
        assertEquals("me", r.manager.activeDeviceId.value)
        r.hear(ServerMessage.Release(12_000, "web1"))
        assertEquals(1, r.local.pauses)
        assertEquals("web1", r.manager.activeDeviceId.value)
        val remote = r.manager.remote.value!!
        assertEquals("Ta", remote.state.current?.title)
        assertTrue(remote.state.isPlaying)
    }

    @Test
    fun release_isIgnoredByADeviceThatWasNeverTheActiveOne() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Release(12_000, "tv"))
        assertEquals(0, r.local.pauses)
        assertEquals("web1", r.manager.activeDeviceId.value)
    }

    @Test
    fun commands_areDoneWhileActive_andIgnoredOtherwise() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Remote(1, "web1", Command.of(CommandAction.NEXT)))
        assertTrue(r.local.commands.isEmpty())

        r.hear(ServerMessage.Takeover(2, remoteState(), true))
        r.sent.clear()
        r.hear(ServerMessage.Remote(3, "web1", Command.of(CommandAction.NEXT)))
        r.hear(ServerMessage.Remote(4, "web1", Command.seek(30_000)))
        r.hear(ServerMessage.Remote(5, "web1", Command.volume(0.2)))
        assertEquals(listOf(CommandAction.NEXT, CommandAction.SEEK, CommandAction.VOLUME), r.local.commands.map { it.action })
        assertEquals(30_000.0, r.local.commands[1].number!!, 0.0)
        // And the result is published, once, after the burst.
        assertTrue(r.sent.isEmpty())
        advanceTimeBy(ConnectManager.PUBLISH_DEBOUNCE_MS + 1)
        assertEquals(1, r.sent.count { it is ClientMessage.State })
    }

    @Test
    fun asARemote_commandsAreSent_andTheButtonsAnswerAtOnce() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState(isPlaying = true)))
        assertTrue(r.manager.remoteCommand(Command.of(CommandAction.TOGGLE)))
        assertEquals(ClientMessage.Remote(command = Command.of(CommandAction.TOGGLE)), r.sent.last())
        assertFalse(r.manager.remote.value!!.state.isPlaying)

        r.manager.remoteCommand(Command.volume(0.9))
        assertEquals(0.9, r.manager.remote.value!!.state.volume, 0.0)
        r.manager.remoteCommand(Command.seek(60_000))
        assertEquals(60_000L, r.manager.remote.value!!.state.positionMs)
        r.manager.remoteCommand(Command.shuffle(false))
        assertFalse(r.manager.remote.value!!.state.shuffle)
        r.manager.remoteCommand(Command.repeat(ConnectRepeat.ONE))
        assertEquals(ConnectRepeat.ONE, r.manager.remote.value!!.state.repeat)
    }

    @Test
    fun optimisticPause_freezesThePlayheadWhereItWas() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState(isPlaying = true, positionMs = 4_000)))
        r.clock = 3_000 // 2 s on the local clock after the welcome: the song is at 6 s
        r.manager.remoteCommand(Command.of(CommandAction.PAUSE))
        val frozen = r.manager.remote.value!!
        assertFalse(frozen.state.isPlaying)
        assertEquals(7_000L, frozen.state.positionMs) // 4_000 + (12_000 - 9_000)
        r.clock = 60_000
        assertEquals(7_000L, r.manager.remotePlayheadMs())
    }

    @Test
    fun aCommandThatCouldNotBeSent_changesNothing() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState(isPlaying = true)))
        r.transport.open = false
        assertFalse(r.manager.remoteCommand(Command.of(CommandAction.TOGGLE)))
        assertTrue(r.manager.remote.value!!.state.isPlaying)
    }

    @Test
    fun hasNoRemoteToDrive_whileItIsTheActiveDevice() = runTest {
        val r = Rig(this)
        r.join()
        r.hear(ServerMessage.Takeover(2, remoteState(), true))
        assertFalse(r.manager.remoteCommand(Command.of(CommandAction.NEXT)))
        assertTrue(r.sent.none { it is ClientMessage.Remote })
    }

    @Test
    fun switchingToAnotherDevice_sendsTheFreshStateFirst_thenTheTransfer() = runTest {
        val r = Rig(this)
        r.join()
        r.sent.clear()
        r.manager.switchTo("web1")
        assertEquals(listOf("State", "Transfer"), r.sent.map { it::class.simpleName })
        assertEquals(ClientMessage.Transfer("web1", true), r.sent[1])
        assertEquals(12_000L, (r.sent[0] as ClientMessage.State).state.positionMs)
    }

    @Test
    fun switchingToAnotherDevice_whilePaused_handsOverPaused() = runTest {
        val r = Rig(this)
        r.join()
        r.local.snap = playing("a", isPlaying = false)
        r.sent.clear()
        r.manager.switchTo("web1")
        assertEquals(ClientMessage.Transfer("web1", false), r.sent[1])
    }

    @Test
    fun switchingBackToThisDevice_asksForTheSession_keepingItPlayingIfItWas() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState(isPlaying = false)))
        r.manager.switchTo("me")
        assertEquals(listOf<ClientMessage>(ClientMessage.Transfer("me", false)), r.sent)
    }

    @Test
    fun switchingToTheDeviceThatAlreadyPlays_doesNothing() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.manager.switchTo("web1")
        assertTrue(r.sent.isEmpty())
    }

    @Test
    fun activeWithNothingShareable_cannotHandOver() = runTest {
        val r = Rig(this)
        r.join()
        r.local.snap = null
        r.sent.clear()
        r.manager.switchTo("web1")
        assertTrue(r.sent.isEmpty())
    }

    @Test
    fun aRestartThatFindsTheRoomStillCallingItActive_restatesWhatItHolds() = runTest {
        val r = Rig(this)
        r.local.snap = null
        r.manager.start("a".repeat(32), ConnectDevice("me", "Pixel 8", "android"), r.local)
        r.transport.listener.onStatus(ConnectStatus.ONLINE)
        r.hear(welcome("me", remoteState()))
        val said = r.sent.single() as ClientMessage.State
        assertEquals(-1, said.state.index) // nothing loaded: an idle state, volume included
        assertEquals(0.7, said.state.volume, 0.0)
        assertNull(r.manager.remote.value)
    }

    @Test
    fun aRoomThatDropsTheActiveDevice_leavesNoRemoteToShow() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.Devices(5, devices.take(2), null))
        assertNull(r.manager.remote.value)
        assertNull(r.manager.activeDeviceId.value)
    }

    @Test
    fun stateFromTheActiveDevice_updatesTheRemote_butNotOurOwnEcho() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState(positionMs = 1_000)))
        r.hear(ServerMessage.State(10_500, "web1", remoteState(positionMs = 5_000)))
        assertEquals(5_000L, r.manager.remote.value!!.state.positionMs)
        assertEquals(10_500L, r.manager.remote.value!!.atServerMs)
        r.hear(ServerMessage.State(10_600, "me", remoteState(positionMs = 99_000)))
        assertEquals(5_000L, r.manager.remote.value!!.state.positionMs)
        r.hear(ServerMessage.State(10_700, "web1", remoteState().copy(index = 7)))
        assertEquals(5_000L, r.manager.remote.value!!.state.positionMs)
    }

    @Test
    fun stateWhileActive_isNotShownAsARemote() = runTest {
        val r = Rig(this)
        r.join()
        r.hear(ServerMessage.State(11_000, "web1", remoteState()))
        assertNull(r.manager.remote.value)
    }

    @Test
    fun publishing_isDebounced_andBeatsEveryThreeSecondsOnlyWhilePlaying() = runTest {
        val r = Rig(this)
        r.join()
        r.manager.onLocalChanged()
        r.manager.onLocalChanged()
        r.manager.onLocalChanged()
        advanceTimeBy(ConnectManager.PUBLISH_DEBOUNCE_MS - 1)
        assertTrue(r.sent.isEmpty())
        advanceTimeBy(2)
        assertEquals(1, r.sent.size)
        r.sent.clear()

        val start = currentTime
        advanceTimeBy(3_000 * 3 + 10)
        assertEquals("one beat per 3 s while playing", 3, r.sent.count { it is ClientMessage.State })
        assertTrue(currentTime > start)

        r.sent.clear()
        r.local.snap = playing("a", isPlaying = false)
        advanceTimeBy(10_000)
        assertTrue("silent while paused", r.sent.isEmpty())
    }

    @Test
    fun aDeviceThatIsNotActive_neverPublishes() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.manager.onLocalChanged()
        advanceTimeBy(10_000)
        assertTrue(r.sent.isEmpty())
    }

    @Test
    fun framesFromAnOlderConnection_areIgnoredAfterStop() = runTest {
        val r = Rig(this)
        r.join(welcome("web1", remoteState()))
        val old = r.transport
        r.manager.stop()
        assertTrue(old.stopped)
        old.listener.onMessage(welcome("web1", remoteState()))
        assertEquals(ConnectStatus.OFF, r.manager.status.value)
        assertTrue(r.manager.devices.value.isEmpty())
        assertNull(r.manager.remote.value)
    }

    @Test
    fun malformedRemoteState_isDropped() = runTest {
        val r = Rig(this)
        r.local.snap = playing("a", isPlaying = false)
        r.join(welcome("web1", remoteState()))
        r.hear(ServerMessage.State(11_000, "web1", remoteState(index = 9)))
        assertEquals(4_000L, r.manager.remote.value!!.state.positionMs)
    }
}
