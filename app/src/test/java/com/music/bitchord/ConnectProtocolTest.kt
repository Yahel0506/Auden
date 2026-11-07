package com.music.bitchord

import com.music.bitchord.data.connect.ClientMessage
import com.music.bitchord.data.connect.Command
import com.music.bitchord.data.connect.CommandAction
import com.music.bitchord.data.connect.ConnectCodec
import com.music.bitchord.data.connect.ConnectDevice
import com.music.bitchord.data.connect.ConnectLimits
import com.music.bitchord.data.connect.ConnectRepeat
import com.music.bitchord.data.connect.ConnectTrack
import com.music.bitchord.data.connect.PlaybackState
import com.music.bitchord.data.connect.ServerMessage
import com.music.bitchord.data.connect.playheadMs
import com.music.bitchord.data.connect.trimmedForWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format against the exact JSON shapes of web/shared/src/connect (protocol.ts, room.test.ts): what
 * the Durable Object accepts must be what we write, and what it sends must be what we read.
 */
class ConnectProtocolTest {

    private fun tree(text: String): JsonElement = Json.parseToJsonElement(text)

    private val state = PlaybackState(
        queue = listOf(
            ConnectTrack("aaaaaaaaaaa", "A", "X"),
            ConnectTrack("bbbbbbbbbbb", "B", "X", album = "Alb", artworkUrl = "https://i.example/b.jpg", durationSec = 200),
        ),
        index = 0, positionMs = 1000, isPlaying = true, volume = 0.5, shuffle = false, repeat = ConnectRepeat.OFF,
    )

    private val stateJson = """{"queue":[{"videoId":"aaaaaaaaaaa","title":"A","artist":"X"},""" +
        """{"videoId":"bbbbbbbbbbb","title":"B","artist":"X","album":"Alb","artworkUrl":"https://i.example/b.jpg","durationSec":200}],""" +
        """"index":0,"positionMs":1000,"isPlaying":true,"volume":0.5,"shuffle":false,"repeat":"off"}"""

    @Test
    fun hello_isWrittenWithVersionAndDeviceAndNoStrayFields() {
        val text = ConnectCodec.encode(ClientMessage.Hello(device = ConnectDevice("dev-1", "Pixel 8", "android")))
        assertEquals(tree("""{"type":"hello","v":1,"device":{"deviceId":"dev-1","name":"Pixel 8","platform":"android"}}"""), tree(text))
    }

    @Test
    fun stateAndClaim_carryTheStateWithoutNulls() {
        assertEquals(tree("""{"type":"state","state":$stateJson}"""), tree(ConnectCodec.encode(ClientMessage.State(state))))
        assertEquals(tree("""{"type":"claim","state":$stateJson}"""), tree(ConnectCodec.encode(ClientMessage.Claim(state))))
    }

    @Test
    fun transfer_and_ping() {
        assertEquals(tree("""{"type":"transfer","to":"web1","play":true}"""), tree(ConnectCodec.encode(ClientMessage.Transfer("web1", true))))
        assertEquals(tree("""{"type":"ping","clientMs":42}"""), tree(ConnectCodec.encode(ClientMessage.Ping(42))))
    }

    @Test
    fun commands_haveTheServersValueShapes_andAbsentToIsOmitted() {
        fun wire(c: Command) = tree(ConnectCodec.encode(ClientMessage.Remote(command = c)))
        assertEquals(tree("""{"type":"command","command":{"action":"pause"}}"""), wire(Command.of(CommandAction.PAUSE)))
        assertEquals(tree("""{"type":"command","command":{"action":"seek","value":30000}}"""), wire(Command.seek(30_000)))
        assertEquals(tree("""{"type":"command","command":{"action":"volume","value":0.25}}"""), wire(Command.volume(0.25)))
        assertEquals(tree("""{"type":"command","command":{"action":"jump","value":3}}"""), wire(Command.jump(3)))
        assertEquals(tree("""{"type":"command","command":{"action":"shuffle","value":true}}"""), wire(Command.shuffle(true)))
        assertEquals(tree("""{"type":"command","command":{"action":"repeat","value":"one"}}"""), wire(Command.repeat(ConnectRepeat.ONE)))
        assertEquals(
            tree("""{"type":"command","to":"tv","command":{"action":"next"}}"""),
            tree(ConnectCodec.encode(ClientMessage.Remote(to = "tv", command = Command.of(CommandAction.NEXT)))),
        )
    }

    @Test
    fun clientMessages_roundTripThroughTheServersParse() {
        val messages = listOf(
            ClientMessage.Hello(device = ConnectDevice("d", "n", "tv")),
            ClientMessage.State(state),
            ClientMessage.Claim(state),
            ClientMessage.Transfer("x", false),
            ClientMessage.Remote("x", Command.seek(5)),
            ClientMessage.Ping(7),
        )
        for (m in messages) assertEquals(m, ConnectCodec.parseClient(ConnectCodec.encode(m)))
    }

    @Test
    fun welcome_parses_withNullActiveDeviceAndNullState() {
        val m = ConnectCodec.parseServer(
            """{"type":"welcome","serverMs":10000,"devices":[{"deviceId":"web1","name":"Chrome on Linux","platform":"web","online":true},""" +
                """{"deviceId":"phone","name":"Pixel","platform":"android","online":false}],"activeDeviceId":null,"state":null,"stateServerMs":0}""",
        ) as ServerMessage.Welcome
        assertEquals(10_000L, m.serverMs)
        assertEquals(listOf(true, false), m.devices.map { it.online })
        assertNull(m.activeDeviceId)
        assertNull(m.state)
    }

    @Test
    fun welcome_parses_withStateAndStamp() {
        val m = ConnectCodec.parseServer(
            """{"type":"welcome","serverMs":10000,"devices":[],"activeDeviceId":"phone","state":$stateJson,"stateServerMs":9000}""",
        ) as ServerMessage.Welcome
        assertEquals("phone", m.activeDeviceId)
        assertEquals(state, m.state)
        assertEquals(9_000L, m.stateServerMs)
    }

    @Test
    fun serverFrames_ofEveryType() {
        assertEquals(
            ServerMessage.Devices(5, listOf(com.music.bitchord.data.connect.DeviceEntry("a", "A", "web", true)), null),
            ConnectCodec.parseServer("""{"type":"devices","serverMs":5,"devices":[{"deviceId":"a","name":"A","platform":"web","online":true}],"activeDeviceId":null}"""),
        )
        assertEquals(ServerMessage.State(7, "web", state), ConnectCodec.parseServer("""{"type":"state","serverMs":7,"from":"web","state":$stateJson}"""))
        // The state the room publishes when the grace period ends comes from an empty device id.
        assertEquals(ServerMessage.State(7, "", state), ConnectCodec.parseServer("""{"type":"state","serverMs":7,"from":"","state":$stateJson}"""))
        assertEquals(
            ServerMessage.Remote(1, "web", Command.of(CommandAction.NEXT)),
            ConnectCodec.parseServer("""{"type":"command","serverMs":1,"from":"web","command":{"action":"next"}}"""),
        )
        assertEquals(ServerMessage.Takeover(5000, state, true), ConnectCodec.parseServer("""{"type":"takeover","serverMs":5000,"state":$stateJson,"play":true}"""))
        assertEquals(ServerMessage.Takeover(5000, null, false), ConnectCodec.parseServer("""{"type":"takeover","serverMs":5000,"state":null,"play":false}"""))
        assertEquals(ServerMessage.Release(5000, "web"), ConnectCodec.parseServer("""{"type":"release","serverMs":5000,"to":"web"}"""))
        assertEquals(ServerMessage.Release(5000, null), ConnectCodec.parseServer("""{"type":"release","serverMs":5000,"to":null}"""))
        assertEquals(ServerMessage.Pong(1, 2), ConnectCodec.parseServer("""{"type":"pong","clientMs":1,"serverMs":2}"""))
        assertEquals(ServerMessage.Error("unknown_device"), ConnectCodec.parseServer("""{"type":"error","code":"unknown_device"}"""))
    }

    @Test
    fun parser_toleratesUnknownFields_butNotGarbage() {
        val m = ConnectCodec.parseServer("""{"type":"pong","clientMs":1,"serverMs":2,"futureField":{"a":[1,2]}}""")
        assertEquals(ServerMessage.Pong(1, 2), m)
        val s = ConnectCodec.parseServer(
            """{"type":"state","serverMs":7,"from":"w","extra":1,"state":${stateJson.replace("\"repeat\":\"off\"", "\"repeat\":\"off\",\"later\":true")}}""",
        )
        assertNotNull(s)
        assertNull(ConnectCodec.parseServer("pong"))
        assertNull(ConnectCodec.parseServer("not json"))
        assertNull(ConnectCodec.parseServer("""{"type":"from_the_future"}"""))
        assertNull(ConnectCodec.parseServer("""{"serverMs":1}"""))
        assertNull(ConnectCodec.parseServer("""{"type":"state","serverMs":7,"from":"w","state":{"queue":"no"}}"""))
    }

    @Test
    fun keepAlive_framesAreNotJson() {
        assertEquals("ping", ConnectCodec.KEEPALIVE)
        assertEquals("pong", ConnectCodec.KEEPALIVE_ANSWER)
        assertNull(ConnectCodec.parseServer(ConnectCodec.KEEPALIVE_ANSWER))
    }

    @Test
    fun wellFormed_mirrorsTheServersChecks() {
        assertTrue(state.isWellFormed())
        assertFalse(state.copy(index = 5).isWellFormed())
        assertFalse(state.copy(index = -2).isWellFormed())
        assertTrue(state.copy(queue = emptyList(), index = -1).isWellFormed())
        assertTrue(state.copy(queue = emptyList(), index = 0).isWellFormed())
        assertFalse(state.copy(positionMs = -1).isWellFormed())
        assertFalse(state.copy(queue = List(101) { state.queue[0] }, index = 0).isWellFormed())
    }

    @Test
    fun playhead_runsWhilePlaying_holdsWhilePaused_neverPastTheEnd() {
        assertEquals(3000L, playheadMs(state.copy(positionMs = 1000), 100, 2100))
        assertEquals(1000L, playheadMs(state.copy(positionMs = 1000, isPlaying = false), 100, 2100))
        assertEquals(5000L, playheadMs(state.copy(positionMs = 1000), 100, 99_100, 5000))
        // A clock that reads earlier than the stamp does not run backwards.
        assertEquals(1000L, playheadMs(state.copy(positionMs = 1000), 500, 100))
    }

    @Test
    fun trimming_keepsAWindowAroundTheCurrentSong_andWithinTextLimits() {
        val long = List(250) { ConnectTrack("v${it.toString().padStart(10, '0')}", "T$it", "A") }
        val trimmed = PlaybackState(long, 120, 0, true, 3.0, false, ConnectRepeat.OFF).trimmedForWire()
        assertEquals(ConnectLimits.MAX_QUEUE, trimmed.queue.size)
        assertEquals(long[120].videoId, trimmed.current?.videoId)
        assertEquals(ConnectLimits.BEHIND, trimmed.index)
        assertEquals(1.0, trimmed.volume, 0.0)
        assertTrue(trimmed.isWellFormed())

        val head = PlaybackState(long, 2, 0, true, 1.0, false, ConnectRepeat.OFF).trimmedForWire()
        assertEquals(2, head.index)
        assertEquals(long[0].videoId, head.queue[0].videoId)

        val wordy = ConnectTrack("x".repeat(100), "t".repeat(500), "a", artworkUrl = "https://x/" + "p".repeat(700))
        val cleaned = PlaybackState(listOf(wordy), 0, 0, false, 1.0, false, ConnectRepeat.OFF).trimmedForWire().queue[0]
        assertEquals(ConnectLimits.MAX_VIDEO_ID, cleaned.videoId.length)
        assertEquals(ConnectLimits.MAX_TEXT, cleaned.title.length)
        assertNull(cleaned.artworkUrl)
    }

    @Test
    fun frameFromANormalQueue_staysUnderTheServersSizeLimit() {
        val full = PlaybackState(
            List(100) { ConnectTrack("v${it.toString().padStart(10, '0')}", "t".repeat(60), "a".repeat(40), "al".repeat(20), "https://i.ytimg.com/" + "p".repeat(80), 200) },
            50, 12345, true, 0.5, true, ConnectRepeat.ALL,
        )
        assertTrue(ConnectCodec.encode(ClientMessage.State(full)).length < ConnectLimits.MAX_MESSAGE_CHARS)
    }

    // ---- Shuffle order, queue-edit commands (the protocol extension) --------------------------------------------

    private val trackA = ConnectTrack("aaaaaaaaaaa", "A", "X")
    private val trackAJson = """{"videoId":"aaaaaaaaaaa","title":"A","artist":"X"}"""

    @Test
    fun order_isWrittenWhileShuffling_andOmittedOtherwise() {
        val shuffled = state.copy(shuffle = true, order = listOf("bbbbbbbbbbb", "aaaaaaaaaaa"))
        val json = tree(ConnectCodec.encode(ClientMessage.State(shuffled))) as kotlinx.serialization.json.JsonObject
        val wire = json["state"] as kotlinx.serialization.json.JsonObject
        assertEquals(tree("""["bbbbbbbbbbb","aaaaaaaaaaa"]"""), wire["order"])
        assertEquals(tree("true"), wire["shuffle"])
        // No order: no key at all (not null), as the server's parser expects.
        val plain = tree(ConnectCodec.encode(ClientMessage.State(state))) as kotlinx.serialization.json.JsonObject
        assertFalse((plain["state"] as kotlinx.serialization.json.JsonObject).containsKey("order"))
    }

    @Test
    fun order_parsesFromTheServer_andIsDroppedWhenShuffleIsOff() {
        val withOrder = stateJson.replace("\"shuffle\":false", "\"shuffle\":true").dropLast(1) + ""","order":["bbbbbbbbbbb","aaaaaaaaaaa"]}"""
        val parsed = (ConnectCodec.parseServer("""{"type":"state","serverMs":7,"from":"w","state":$withOrder}""") as ServerMessage.State).state
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), parsed.order)
        assertEquals(parsed, parsed.normalized())

        // shuffle off but an order sent anyway: the parser (and so we) drop it.
        val stale = parsed.copy(shuffle = false)
        assertEquals(listOf("bbbbbbbbbbb", "aaaaaaaaaaa"), stale.order)
        assertNull(stale.normalized().order)
    }

    @Test
    fun order_mustBeWithinTheServersLimits() {
        assertTrue(state.copy(shuffle = true, order = listOf("a")).isWellFormed())
        assertFalse(state.copy(shuffle = true, order = List(101) { "a" }).isWellFormed())
        assertFalse(state.copy(shuffle = true, order = listOf("x".repeat(65))).isWellFormed())
    }

    @Test
    fun trimming_cutsTheOrderToThePermutationOfWhatIsSent() {
        val long = List(150) { ConnectTrack("v${it.toString().padStart(10, '0')}", "T$it", "A") }
        val order = long.map { it.videoId }.reversed()
        val trimmed = PlaybackState(long, 120, 0, true, 1.0, true, ConnectRepeat.OFF, order).trimmedForWire()
        assertEquals(trimmed.queue.map { it.videoId }.toSet(), trimmed.order!!.toSet())
        assertEquals(trimmed.queue.size, trimmed.order!!.size)
        // Still in the reversed relative order.
        val expected = order.filter { id -> trimmed.queue.any { it.videoId == id } }
        assertEquals(expected, trimmed.order)
        assertTrue(trimmed.isWellFormed())
        // Shuffle off sends none; an empty queue sends none.
        assertNull(PlaybackState(long, 120, 0, true, 1.0, false, ConnectRepeat.OFF, order).trimmedForWire().order)
        assertNull(PlaybackState(emptyList(), -1, 0, false, 1.0, true, ConnectRepeat.OFF, order).trimmedForWire().order)
    }

    @Test
    fun queueEditCommands_haveTheServersShapes() {
        fun wire(c: Command) = tree(ConnectCodec.encode(ClientMessage.Remote(command = c)))
        assertEquals(tree("""{"type":"command","command":{"action":"remove","value":4}}"""), wire(Command.remove(4)))
        assertEquals(tree("""{"type":"command","command":{"action":"move","value":2,"to":0}}"""), wire(Command.move(2, 0)))
        assertEquals(tree("""{"type":"command","command":{"action":"clearUpcoming"}}"""), wire(Command.clearUpcoming()))
        assertEquals(
            tree("""{"type":"command","command":{"action":"playNext","tracks":[$trackAJson]}}"""),
            wire(Command.playNext(listOf(trackA))),
        )
        assertEquals(
            tree("""{"type":"command","command":{"action":"addToQueue","tracks":[$trackAJson]}}"""),
            wire(Command.addToQueue(listOf(trackA))),
        )
    }

    @Test
    fun queueEditCommands_parseFromTheServer_andRoundTrip() {
        val m = ConnectCodec.parseServer(
            """{"type":"command","serverMs":1,"from":"web","command":{"action":"move","value":3,"to":1}}""",
        ) as ServerMessage.Remote
        assertEquals(CommandAction.MOVE, m.command.action)
        assertEquals(3, m.command.index)
        assertEquals(1, m.command.to)
        val add = ConnectCodec.parseServer(
            """{"type":"command","serverMs":1,"from":"web","command":{"action":"addToQueue","tracks":[$trackAJson]}}""",
        ) as ServerMessage.Remote
        assertEquals(listOf(trackA), add.command.tracks)
        for (c in listOf(Command.remove(1), Command.move(1, 2), Command.clearUpcoming(), Command.playNext(listOf(trackA)), Command.addToQueue(listOf(trackA)))) {
            assertEquals(ClientMessage.Remote("x", c), ConnectCodec.parseClient(ConnectCodec.encode(ClientMessage.Remote("x", c))))
        }
    }

    @Test
    fun trimmedOrder_keepsDuplicatesAndAppendsWhatTheOrderForgot() {
        val window = listOf(ConnectTrack("aaaaaaaaaaa", "A", "X"), ConnectTrack("bbbbbbbbbbb", "B", "X"), ConnectTrack("aaaaaaaaaaa", "A", "X"), ConnectTrack("ccccccccccc", "C", "X"))
        // The order forgets "c" (queued after the shuffle) and names a song that left the queue.
        val out = com.music.bitchord.data.connect.trimmedOrder(listOf("aaaaaaaaaaa", "gone", "bbbbbbbbbbb", "aaaaaaaaaaa"), window)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa", "ccccccccccc"), out)
    }
}
