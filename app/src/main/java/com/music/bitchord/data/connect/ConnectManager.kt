package com.music.bitchord.data.connect

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What this device's player offers Auden Connect, and what Connect asks of it.
 * [com.music.bitchord.playback.ConnectSync] answers it with the ExoPlayer; a test answers it with a fake.
 *
 * Every method is called on the manager's scope (the main thread in the app), which is also the player's.
 */
interface ConnectLocal {
    /** What is loaded as the protocol states it, or null when there is nothing to share (empty, a local file, a DJ clip). */
    fun snapshot(): PlaybackState?

    /** The loaded queue is a Listen Together party's: not a session to carry to another device. */
    fun isPartyQueue(): Boolean

    /** This device's own output level, 0..1. */
    fun volume(): Double

    /** Replace the queue with [state]'s, at its index and position, and sound when [play]; [done] when applied. */
    fun takeover(state: PlaybackState, play: Boolean, done: () -> Unit)

    /** Stop sounding, keeping the queue (this device just became a remote). */
    fun pause()

    /** Do what a remote asked of the active device. */
    fun applyCommand(command: Command)
}

/** What another device is playing, as it last said so, and the server time it said it. */
data class RemoteSession(val state: PlaybackState, val atServerMs: Long)

/**
 * Auden Connect, the logic: one signed-in account's devices handing playback to each other.
 * `web/app/src/auden/connect/session.ts` is the same thing for the browser and this class keeps its semantics.
 *
 * This device is either the ACTIVE one (it makes the sound and publishes what it plays) or a REMOTE (its
 * player is silent, and [remote] is what the active one last said). The room only ever has one active device.
 *
 * Who may claim: only when the user starts playback on this phone ([onLocalIntent], fed by the player's
 * user-action door) or when the socket (re)connects with something already playing. A release pauses the
 * player, and nothing the player then does by itself brings the claim back; so a remote session stays put
 * until the user presses play here.
 *
 * Threading: everything runs on [scope] (the main thread in the app); socket frames are posted onto it.
 */
class ConnectManager(
    private val scope: CoroutineScope,
    private val factory: ConnectTransportFactory,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = PUBLISH_DEBOUNCE_MS,
    private val heartbeatMs: Long = ConnectLimits.STATE_HEARTBEAT_MS,
    private val intentSettleMs: Long = INTENT_SETTLE_MS,
) {
    private val _status = MutableStateFlow(ConnectStatus.OFF)
    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    private val _activeDeviceId = MutableStateFlow<String?>(null)
    private val _remote = MutableStateFlow<RemoteSession?>(null)
    private val _mirroring = MutableStateFlow(false)

    val status: StateFlow<ConnectStatus> = _status.asStateFlow()

    /** The account's devices that are online now (this one included), in the room's order. */
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()
    val activeDeviceId: StateFlow<String?> = _activeDeviceId.asStateFlow()

    /** Another device's playback while it is the active one; null when this device is, or nothing is. */
    val remote: StateFlow<RemoteSession?> = _remote.asStateFlow()

    /**
     * True while this phone's player shows another device's session as its own, silent: the queue is kept in the
     * player, the transport goes to [remoteCommand] and the clock is [remotePlayheadMs]. It is [isRemote] unless
     * the user started something else here ([leaveMirror]), until the room's active device changes.
     */
    val mirroring: StateFlow<Boolean> = _mirroring.asStateFlow()

    var myId: String = ""
        private set

    /** serverNow - localNow, from the welcome. */
    var offsetMs: Long = 0L
        private set

    private var transport: ConnectTransport? = null
    private var local: ConnectLocal? = null
    private var run = 0
    private var applying = false
    private var debounce: Job? = null
    private var intent: Job? = null
    private var heartbeat: Job? = null

    /** The user started something of their own here while another device was playing; see [leaveMirror]. */
    private var leftMirror = false

    val isActive: Boolean get() = myId.isNotEmpty() && _activeDeviceId.value == myId

    /** True while another device is the one making sound and this one is its remote. */
    val isRemote: Boolean get() = _remote.value != null && _activeDeviceId.value.let { it != null && it != myId }

    val isMirroring: Boolean get() = _mirroring.value

    private fun setActive(id: String?) {
        if (id != _activeDeviceId.value) leftMirror = false
        _activeDeviceId.value = id
        refreshMirroring()
    }

    private fun setRemote(session: RemoteSession?) {
        _remote.value = session
        refreshMirroring()
    }

    private fun refreshMirroring() {
        _mirroring.value = local != null && !leftMirror && isRemote
    }

    /**
     * The user starts something new on this phone while it mirrors another device: the mirror ends, the player is
     * the phone's own again, and the claim that follows ([onLocalIntent]) makes the phone the active device. When
     * nothing can be claimed (a local file, a party) the other device just plays on and the phone stays out of it
     * until the active device changes.
     */
    fun leaveMirror() {
        if (!_mirroring.value) return
        leftMirror = true
        refreshMirroring()
    }

    fun serverNowMs(): Long = nowMs() + offsetMs

    /** Where the remote song is now, in ms, by the server's clock; 0 without one. */
    fun remotePlayheadMs(): Long {
        val r = _remote.value ?: return 0L
        val durationMs = r.state.current?.durationSec?.let { it * 1000L }
        return playheadMs(r.state, r.atServerMs, serverNowMs(), durationMs)
    }

    /** Joins [room] as [device] until [stop]. Calling it again leaves the first room. */
    fun start(room: String, device: ConnectDevice, local: ConnectLocal) {
        stop()
        this.local = local
        myId = device.deviceId
        val mine = ++run
        val listener = object : ConnectTransportListener {
            override fun onMessage(message: ServerMessage) {
                scope.launch { if (run == mine) handle(message) }
            }

            override fun onStatus(status: ConnectStatus) {
                scope.launch { if (run == mine) _status.value = status }
            }
        }
        val t = factory.create(room, device, listener)
        transport = t
        t.start()
        heartbeat = scope.launch {
            while (true) {
                delay(heartbeatMs)
                if (isActive && local.snapshot()?.isPlaying == true) publish()
            }
        }
    }

    fun stop() {
        run++
        heartbeat?.cancel(); heartbeat = null
        debounce?.cancel(); debounce = null
        intent?.cancel(); intent = null
        transport?.stop()
        transport = null
        local = null
        applying = false
        leftMirror = false
        _status.value = ConnectStatus.OFF
        _devices.value = emptyList()
        _activeDeviceId.value = null
        _remote.value = null
        _mirroring.value = false
    }

    /** The network came back: do not wait out the backoff. */
    fun nudge() {
        transport?.nudge()
    }

    // ---- The player told us something --------------------------------------------------------

    /**
     * The player changed (track, queue, play state, shuffle, repeat, volume, a seek). The active device says
     * so to the room; any other device ignores it, because only the user starting playback claims.
     */
    fun onLocalChanged() {
        if (isActive) publishSoon()
    }

    /**
     * The user did something to this player. If this is not the active device and it is now playing, that
     * is "start playback here": claim. Delayed a moment because one gesture is several player calls
     * (queue, prepare, play) and the claim must carry the finished queue.
     */
    fun onLocalIntent() {
        if (local == null) return
        if (isActive) {
            publishSoon()
            return
        }
        intent?.cancel()
        intent = scope.launch {
            delay(intentSettleMs)
            claimIfPlaying()
        }
    }

    // ---- Sending ----------------------------------------------------------------------------

    private fun send(message: ClientMessage): Boolean = transport?.send(message) ?: false

    /** The active device says where it is. */
    private fun publish() {
        val l = local ?: return
        if (!isActive || applying) return
        val state = l.snapshot() ?: PlaybackState.IDLE.copy(volume = l.volume())
        send(ClientMessage.State(state.trimmedForWire()))
    }

    private fun publishSoon() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(debounceMs)
            publish()
        }
    }

    // ---- Becoming active, and giving it up ----------------------------------------------------

    private fun claimIfPlaying() {
        val l = local ?: return
        if (_status.value != ConnectStatus.ONLINE || isActive || applying) return
        // A party's queue is the party's, not a session to carry to another device.
        if (l.isPartyQueue()) return
        val state = l.snapshot() ?: return
        if (!state.isPlaying) return
        setActive(myId)
        setRemote(null)
        send(ClientMessage.Claim(state.trimmedForWire()))
    }

    private fun takeover(state: PlaybackState?, play: Boolean) {
        val l = local ?: return
        setActive(myId)
        setRemote(null)
        if (state == null || state.index < 0 || state.queue.isEmpty() || !state.isWellFormed()) return
        applying = true
        l.takeover(state, play) {
            applying = false
            publish()
        }
    }

    private fun release(to: String?) {
        val l = local ?: return
        val mine = l.snapshot()
        l.pause()
        setActive(to)
        setRemote(mine?.let { RemoteSession(it.copy(isPlaying = true), serverNowMs()) })
    }

    // ---- Messages ---------------------------------------------------------------------------

    internal fun handle(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                offsetMs = message.serverMs - nowMs()
                _devices.value = message.devices.filter { it.online }
                setActive(message.activeDeviceId)
                setRemote(
                    message.state?.normalized()
                        ?.takeIf { message.activeDeviceId != null && message.activeDeviceId != myId && it.isWellFormed() }
                        ?.let { RemoteSession(it, message.stateServerMs) },
                )
                // A restart leaves the room thinking this device still plays: say what it really holds.
                if (message.activeDeviceId == myId) publish() else claimIfPlaying()
            }

            is ServerMessage.Devices -> {
                _devices.value = message.devices.filter { it.online }
                setActive(message.activeDeviceId)
                if (message.activeDeviceId == null || message.activeDeviceId == myId) setRemote(null)
            }

            is ServerMessage.State -> {
                if (message.from.isNotEmpty() && message.from == myId) return
                val state = message.state.normalized()
                if (!state.isWellFormed()) return
                setRemote(if (isActive) null else RemoteSession(state, message.serverMs))
            }

            is ServerMessage.Remote -> {
                if (isActive) {
                    local?.applyCommand(message.command)
                    publishSoon()
                }
            }

            is ServerMessage.Takeover -> takeover(message.state?.normalized(), message.play)

            is ServerMessage.Release ->
                if (isActive || _activeDeviceId.value == null) release(message.to)

            is ServerMessage.Pong -> Unit
            is ServerMessage.Error -> Log.w(TAG, "connect error: ${message.code}")
        }
    }

    // ---- What the user does -----------------------------------------------------------------

    /** Plays on [targetId]: this device or another one. */
    fun switchTo(targetId: String) {
        val l = local ?: return
        if (targetId == _activeDeviceId.value) return
        val remoteNow = _remote.value
        if (targetId == myId) {
            send(ClientMessage.Transfer(myId, remoteNow?.state?.isPlaying ?: true))
            return
        }
        val mine = if (isActive) l.snapshot() else null
        if (isActive && mine == null) return
        // The room hands over the last state it holds: make it this moment's.
        if (mine != null) send(ClientMessage.State(mine.trimmedForWire()))
        send(ClientMessage.Transfer(targetId, mine?.isPlaying ?: (remoteNow?.state?.isPlaying ?: true)))
    }

    /**
     * A control for the device that is playing (the mirrored player's buttons, a queue edit); false when there is
     * none to drive. The session shown here is updated at once as the command will leave it, and the active
     * device's next state is the truth.
     */
    fun remoteCommand(command: Command): Boolean {
        val current = _remote.value
        if (current == null || isActive) return false
        val sent = send(ClientMessage.Remote(command = command))
        if (sent) {
            val state = current.state
            val now = serverNowMs()
            val head = remotePlayheadMs()
            val next = state.afterCommand(command, head)
            if (next !== state) {
                // A command that moves the playhead or the play state starts a new clock; a queue edit leaves it.
                setRemote(RemoteSession(next, if (command.reanchors()) now else current.atServerMs))
            }
        }
        return sent
    }

    companion object {
        private const val TAG = "ConnectManager"
        const val PUBLISH_DEBOUNCE_MS = 120L
        const val INTENT_SETTLE_MS = 200L

        /** The app's one manager; the playback service starts it and the UI reads it. */
        val shared: ConnectManager by lazy {
            ConnectManager(
                scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
                factory = ConnectTransportFactory { room, device, listener ->
                    ConnectClient(
                        serverUrl = com.music.bitchord.BuildConfig.CONNECT_SERVER,
                        room = room,
                        device = device,
                        listener = listener,
                        scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
                    )
                },
            )
        }
    }
}
