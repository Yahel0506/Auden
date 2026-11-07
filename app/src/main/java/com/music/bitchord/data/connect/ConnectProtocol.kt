package com.music.bitchord.data.connect

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Auden Connect's wire format, field for field the one in `web/shared/src/connect/protocol.ts`.
 *
 * Everything is camelCase JSON with a `type` discriminator; optional fields are simply absent. The two
 * platforms speak to the same Durable Object, so anything renamed here has to be renamed there. The
 * keep-alive is not a message: it is the bare text frame `ping`, answered by the bare text frame `pong`.
 */
const val CONNECT_PROTOCOL = 1

/** What a server will accept; clients trim to these before sending (the server drops anything over them). */
object ConnectLimits {
    const val MAX_MESSAGE_CHARS = 96 * 1024
    const val MAX_QUEUE = 100
    const val MAX_DEVICES = 8
    const val MAX_TEXT = 300
    const val MAX_VIDEO_ID = 64
    const val MAX_ARTWORK = 600
    const val MAX_DEVICE_NAME = 80

    /** How long the room keeps a session whose active device went offline. */
    const val ACTIVE_GRACE_MS = 20_000L

    /** The active device re-states its position this often while playing. */
    const val STATE_HEARTBEAT_MS = 3_000L

    /** How much of a long queue is carried: some of what played, all of what is next, up to [MAX_QUEUE]. */
    const val BEHIND = 10
    const val AHEAD = 89
}

object ConnectPlatform {
    const val WEB = "web"
    const val ANDROID = "android"
    const val TV = "tv"
    const val WATCH = "watch"
    const val PC = "pc"
}

@Serializable
data class ConnectDevice(
    val deviceId: String,
    val name: String,
    val platform: String,
)

/** A song in the shared order: enough for any device to show it and to load it from YouTube. */
@Serializable
data class ConnectTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationSec: Int? = null,
)

@Serializable
enum class ConnectRepeat {
    @SerialName("off") OFF,
    @SerialName("all") ALL,
    @SerialName("one") ONE,
}

@Serializable
data class PlaybackState(
    /** The whole running order (the playing song included), at most [ConnectLimits.MAX_QUEUE] long. */
    val queue: List<ConnectTrack>,
    /** Position of the playing song in [queue]; -1 when nothing is loaded. */
    val index: Int,
    /** Where the song was, in ms, at the moment the active device sent this. */
    val positionMs: Long,
    val isPlaying: Boolean,
    /** 0..1, the active device's own volume. */
    val volume: Double,
    val shuffle: Boolean,
    val repeat: ConnectRepeat,
    /**
     * While shuffle is on: the queue's own order from before the shuffle, as videoIds, so any device can turn
     * shuffle off and land on the same list. Absent when shuffle is off ([normalized] drops it, as the server's
     * parser does).
     */
    val order: List<String>? = null,
) {
    val current: ConnectTrack? get() = queue.getOrNull(index)

    /** What the server's `parsePlaybackState` makes of this: [order] only travels while shuffle is on. */
    fun normalized(): PlaybackState = if (!shuffle && order != null) copy(order = null) else this

    /** The same checks the server's `parsePlaybackState` makes, so a bad frame is dropped rather than applied. */
    fun isWellFormed(): Boolean =
        queue.size <= ConnectLimits.MAX_QUEUE &&
            index >= -1 && index < maxOf(queue.size, 1) &&
            positionMs >= 0 && volume.isFinite() &&
            queue.all { it.videoId.isNotEmpty() } &&
            (order == null || (order.size <= ConnectLimits.MAX_QUEUE && order.all { it.length <= ConnectLimits.MAX_VIDEO_ID }))

    companion object {
        val IDLE = PlaybackState(emptyList(), -1, 0, false, 1.0, false, ConnectRepeat.OFF)
    }
}

@Serializable
enum class CommandAction {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("toggle") TOGGLE,
    @SerialName("next") NEXT,
    @SerialName("previous") PREVIOUS,
    @SerialName("seek") SEEK,
    @SerialName("volume") VOLUME,
    @SerialName("shuffle") SHUFFLE,
    @SerialName("repeat") REPEAT,
    @SerialName("jump") JUMP,

    // Queue edits: the active device applies them and publishes the new queue, so every device shows the same one.
    @SerialName("playNext") PLAY_NEXT,
    @SerialName("addToQueue") ADD_TO_QUEUE,
    @SerialName("remove") REMOVE,
    @SerialName("move") MOVE,
    @SerialName("clearUpcoming") CLEAR_UPCOMING,
}

/**
 * seek: ms; volume: 0..1; jump / remove: queue index; move: the index to take ([value]) and where it goes ([to]);
 * shuffle: boolean; repeat: "off" | "all" | "one"; playNext / addToQueue: the songs, in order ([tracks]).
 */
@Serializable
data class Command(
    val action: CommandAction,
    val value: JsonPrimitive? = null,
    val to: Int? = null,
    val tracks: List<ConnectTrack>? = null,
) {
    val number: Double? get() = value?.takeIf { !it.isString }?.doubleOrNull
    val flag: Boolean? get() = value?.takeIf { !it.isString }?.booleanOrNull
    val index: Int? get() = number?.takeIf { it >= 0 }?.toInt()
    val repeatMode: ConnectRepeat? get() = when (value?.takeIf { it.isString }?.contentOrNull) {
        "off" -> ConnectRepeat.OFF
        "all" -> ConnectRepeat.ALL
        "one" -> ConnectRepeat.ONE
        else -> null
    }

    companion object {
        fun of(action: CommandAction) = Command(action)
        fun seek(ms: Long) = Command(CommandAction.SEEK, JsonPrimitive(ms))
        fun volume(level: Double) = Command(CommandAction.VOLUME, JsonPrimitive(level))
        fun jump(index: Int) = Command(CommandAction.JUMP, JsonPrimitive(index))
        fun remove(index: Int) = Command(CommandAction.REMOVE, JsonPrimitive(index))
        fun move(from: Int, to: Int) = Command(CommandAction.MOVE, JsonPrimitive(from), to = to)
        fun clearUpcoming() = Command(CommandAction.CLEAR_UPCOMING)
        fun playNext(tracks: List<ConnectTrack>) = Command(CommandAction.PLAY_NEXT, tracks = tracks)
        fun addToQueue(tracks: List<ConnectTrack>) = Command(CommandAction.ADD_TO_QUEUE, tracks = tracks)
        fun shuffle(on: Boolean) = Command(CommandAction.SHUFFLE, JsonPrimitive(on))
        fun repeat(mode: ConnectRepeat) = Command(
            CommandAction.REPEAT,
            JsonPrimitive(
                when (mode) {
                    ConnectRepeat.OFF -> "off"
                    ConnectRepeat.ALL -> "all"
                    ConnectRepeat.ONE -> "one"
                },
            ),
        )
    }
}

/** A device of the account and whether its socket is open right now. */
@Serializable
data class DeviceEntry(
    val deviceId: String,
    val name: String,
    val platform: String,
    val online: Boolean,
)

// ---- Client -> server --------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(@EncodeDefault val v: Int = CONNECT_PROTOCOL, val device: ConnectDevice) : ClientMessage

    /** The active device's playback state; sent on every change and every few seconds while playing. */
    @Serializable
    @SerialName("state")
    data class State(val state: PlaybackState) : ClientMessage

    /** "I am starting playback here": this device becomes the active one and the previous one is released. */
    @Serializable
    @SerialName("claim")
    data class Claim(val state: PlaybackState) : ClientMessage

    /** Hand playback to [to]; [play] says whether it should be sounding when it lands. */
    @Serializable
    @SerialName("transfer")
    data class Transfer(val to: String, val play: Boolean) : ClientMessage

    /** A remote control for the active device (or for [to]). */
    @Serializable
    @SerialName("command")
    data class Remote(val to: String? = null, val command: Command) : ClientMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val clientMs: Long) : ClientMessage
}

// ---- Server -> client --------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage {
    @Serializable
    @SerialName("welcome")
    data class Welcome(
        val serverMs: Long,
        val devices: List<DeviceEntry>,
        val activeDeviceId: String? = null,
        val state: PlaybackState? = null,
        val stateServerMs: Long = 0,
    ) : ServerMessage

    @Serializable
    @SerialName("devices")
    data class Devices(
        val serverMs: Long,
        val devices: List<DeviceEntry>,
        val activeDeviceId: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("state")
    data class State(val serverMs: Long, val from: String, val state: PlaybackState) : ServerMessage

    @Serializable
    @SerialName("command")
    data class Remote(val serverMs: Long, val from: String, val command: Command) : ServerMessage

    /** You are the active device now: load [state] and, when [play], sound. State is null for a bare takeover. */
    @Serializable
    @SerialName("takeover")
    data class Takeover(val serverMs: Long, val state: PlaybackState? = null, val play: Boolean) : ServerMessage

    /** You are no longer the active device: stop, and show [to]'s playback as a remote. */
    @Serializable
    @SerialName("release")
    data class Release(val serverMs: Long, val to: String? = null) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val clientMs: Long, val serverMs: Long) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(val code: String) : ServerMessage
}

/** Encodes and parses frames. Unknown fields are ignored, and a frame that is not ours parses to null. */
object ConnectCodec {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        // Always written (the hello's `v`), while nulls are left out: "optional fields may be absent".
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
    }

    /** The keep-alive frames: not JSON. */
    const val KEEPALIVE = "ping"
    const val KEEPALIVE_ANSWER = "pong"

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    fun parseServer(text: String): ServerMessage? {
        if (text.length > ConnectLimits.MAX_MESSAGE_CHARS * 2) return null
        return runCatching { json.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()
    }

    /** The server's side of the parse, for tests that play the room. */
    fun parseClient(text: String): ClientMessage? =
        runCatching { json.decodeFromString(ClientMessage.serializer(), text) }.getOrNull()
}

/**
 * Where the song is now, in ms: the state's position plus what has passed since it was true.
 * `playheadMs` in protocol.ts.
 */
fun playheadMs(state: PlaybackState, stateServerMs: Long, serverNowMs: Long, durationMs: Long? = null): Long {
    val elapsed = if (state.isPlaying) maxOf(0L, serverNowMs - stateServerMs) else 0L
    val at = state.positionMs + elapsed
    return if (durationMs != null && durationMs > 0) minOf(at, durationMs) else at
}

/** [state] cut to what the server accepts: texts and artwork within limits, a window of the queue around [PlaybackState.index]. */
fun PlaybackState.trimmedForWire(): PlaybackState {
    if (queue.isEmpty()) return copy(index = -1, order = null)
    val from = maxOf(0, index - ConnectLimits.BEHIND)
    val to = minOf(queue.size, from + ConnectLimits.MAX_QUEUE)
    val window = queue.subList(from, to).map { it.cleaned() }
    val newIndex = if (index in from until to) index - from else -1
    return copy(
        queue = window,
        index = newIndex,
        volume = volume.coerceIn(0.0, 1.0),
        positionMs = maxOf(0, positionMs),
        order = if (shuffle) order?.let { trimmedOrder(it, window) } else null,
    )
}

/**
 * The unshuffled order cut to the songs that are still in [window] (each id as many times as the window holds it),
 * so it is always a permutation of what is sent.
 */
internal fun trimmedOrder(order: List<String>, window: List<ConnectTrack>): List<String> {
    val left = HashMap<String, Int>()
    window.forEach { left.merge(it.videoId.take(ConnectLimits.MAX_VIDEO_ID), 1, Int::plus) }
    val out = ArrayList<String>(window.size)
    for (id in order) {
        val key = id.take(ConnectLimits.MAX_VIDEO_ID)
        val n = left[key] ?: continue
        if (n <= 0) continue
        left[key] = n - 1
        out += key
    }
    // Songs added after the shuffle were never part of the old order: they keep their place at the end.
    for (track in window) {
        val key = track.videoId.take(ConnectLimits.MAX_VIDEO_ID)
        val n = left[key] ?: continue
        if (n > 0) {
            left[key] = n - 1
            out += key
        }
    }
    return out
}

private fun ConnectTrack.cleaned(): ConnectTrack = copy(
    videoId = videoId.take(ConnectLimits.MAX_VIDEO_ID),
    title = title.take(ConnectLimits.MAX_TEXT),
    artist = artist.take(ConnectLimits.MAX_TEXT),
    album = album?.take(ConnectLimits.MAX_TEXT),
    // Too long is not truncated (a cut URL is a broken one) and a non-http one is the server's to drop: leave both out.
    artworkUrl = artworkUrl?.takeIf { it.startsWith("http") && it.length <= ConnectLimits.MAX_ARTWORK },
    durationSec = durationSec?.takeIf { it >= 0 },
)
