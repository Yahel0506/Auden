package com.music.bitchord.data.connect

/**
 * Several rooms as one, the Kotlin side of `web/shared/src/connect/merge.ts` (which says why, and what the rules
 * are). A device with no Google account is in its Spotify account's room and in the one for its network at the
 * same time; this folds their message streams into the one the manager reads.
 */
class RoomMerger(private val myId: String) {
    private class View(val devices: List<DeviceEntry>, val active: String?)

    private val rooms = LinkedHashMap<Int, View>()
    private var welcomed = false
    private var stateRoom: Int? = null
    private val lastOnce = HashMap<String, Long>()

    /** What to deliver to the manager for [message] from room number [room]. */
    fun feed(room: Int, message: ServerMessage, nowMs: Long = System.currentTimeMillis()): List<ServerMessage> = when (message) {
        is ServerMessage.Welcome -> {
            rooms[room] = View(message.devices, message.activeDeviceId)
            val active = active()
            if (!welcomed) {
                welcomed = true
                val mine = message.activeDeviceId == active
                listOf(message.copy(devices = devices(), activeDeviceId = active, state = if (mine) message.state else null))
            } else {
                buildList {
                    add(devicesMessage(message.serverMs))
                    val s = message.state
                    if (s != null && message.activeDeviceId != null && message.activeDeviceId == active && active != myId) {
                        add(ServerMessage.State(message.stateServerMs, active, s))
                    }
                }
            }
        }

        is ServerMessage.Devices -> {
            rooms[room] = View(message.devices, message.activeDeviceId)
            listOf(devicesMessage(message.serverMs))
        }

        is ServerMessage.State -> {
            if (message.from.isNotEmpty() && message.from == myId) emptyList()
            else {
                // The first room to report a state keeps the say until it has no active device of its own.
                if (stateRoom == null || rooms[stateRoom]?.active == null) stateRoom = room
                val view = rooms[room]
                val active = active()
                if (view != null && active != null && view.active != active) emptyList() else listOf(message)
            }
        }

        is ServerMessage.Takeover, is ServerMessage.Release -> {
            val kind = message::class.java.simpleName
            val last = lastOnce[kind]
            if (last != null && nowMs - last < DUPLICATE_MS) emptyList()
            else {
                lastOnce[kind] = nowMs
                listOf(message)
            }
        }

        else -> listOf(message)
    }

    /** A room's socket went away for good: its devices are no longer listed. Null when no room is left. */
    fun drop(room: Int, serverMs: Long = System.currentTimeMillis()): ServerMessage? {
        if (rooms.remove(room) == null) return null
        if (stateRoom == room) stateRoom = null
        return if (rooms.isNotEmpty()) devicesMessage(serverMs) else null
    }

    private fun devicesMessage(serverMs: Long) = ServerMessage.Devices(serverMs, devices(), active())

    private fun devices(): List<DeviceEntry> {
        val byId = LinkedHashMap<String, DeviceEntry>()
        for (view in rooms.values) for (d in view.devices) {
            val seen = byId[d.deviceId]
            byId[d.deviceId] = if (seen != null) seen.copy(online = seen.online || d.online) else d
        }
        return byId.values.toList()
    }

    private fun active(): String? {
        if (rooms.values.any { it.active == myId }) return myId
        stateRoom?.let { rooms[it]?.active }?.let { return it }
        return rooms.values.firstNotNullOfOrNull { it.active }
    }

    private companion object {
        const val DUPLICATE_MS = 1_000L
    }
}

/**
 * The transports of several rooms as one: a socket per room, their messages folded by a [RoomMerger] and what is
 * sent going to every open room (a room that does not hold the target of a command or a transfer ignores it).
 * [rooms] are the room ids the manager was given, joined by [SEPARATOR].
 */
class MultiRoomTransport(
    rooms: List<String>,
    device: ConnectDevice,
    private val listener: ConnectTransportListener,
    private val factory: ConnectTransportFactory,
) : ConnectTransport {
    private val merger = RoomMerger(device.deviceId)
    private val statuses = Array(rooms.size) { ConnectStatus.OFF }
    private var lastStatus = ConnectStatus.OFF
    private val transports: List<ConnectTransport> = rooms.mapIndexed { index, room ->
        factory.create(room, device, object : ConnectTransportListener {
            override fun onMessage(message: ServerMessage) {
                synchronized(this@MultiRoomTransport) {
                    merger.feed(index, message)
                }.forEach(listener::onMessage)
            }

            override fun onStatus(status: ConnectStatus) {
                val merged = synchronized(this@MultiRoomTransport) {
                    statuses[index] = status
                    val next = when {
                        statuses.any { it == ConnectStatus.ONLINE } -> ConnectStatus.ONLINE
                        statuses.any { it == ConnectStatus.CONNECTING } -> ConnectStatus.CONNECTING
                        else -> ConnectStatus.OFF
                    }
                    next.takeIf { it != lastStatus }?.also { lastStatus = it }
                }
                if (merged != null) listener.onStatus(merged)
            }
        })
    }

    override fun start() = transports.forEach(ConnectTransport::start)

    override fun stop() = transports.forEach(ConnectTransport::stop)

    override fun send(message: ClientMessage): Boolean {
        var sent = false
        for (t in transports) sent = t.send(message) || sent
        return sent
    }

    override fun nudge() = transports.forEach(ConnectTransport::nudge)

    companion object {
        /** Joins room ids into the one string the manager carries. */
        const val SEPARATOR = ","

        fun join(rooms: List<String>): String = rooms.joinToString(SEPARATOR)
    }
}
