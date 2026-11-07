package com.music.bitchord

import com.music.bitchord.data.connect.ClientMessage
import com.music.bitchord.data.connect.ConnectClient
import com.music.bitchord.data.connect.ConnectCodec
import com.music.bitchord.data.connect.ConnectDevice
import com.music.bitchord.data.connect.ConnectStatus
import com.music.bitchord.data.connect.ConnectTransportListener
import com.music.bitchord.data.connect.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The real socket against a local server: hello first, the text keep-alive, dead detection and reconnecting. */
class ConnectClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val device = ConnectDevice("dev-1", "Pixel 8", "android")
    private val welcome = """{"type":"welcome","serverMs":1,"devices":[],"activeDeviceId":null,"state":null,"stateServerMs":0}"""

    /** What the fake room saw: every text frame of every connection, in order, with the connection's number. */
    private val frames = LinkedBlockingQueue<Pair<Int, String>>()
    private val openers = CopyOnWriteArrayList<okhttp3.Headers>()
    private var connections = 0
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    private class Listener : ConnectTransportListener {
        val messages = LinkedBlockingQueue<ServerMessage>()
        val statuses = CopyOnWriteArrayList<ConnectStatus>()
        override fun onMessage(message: ServerMessage) { messages += message }
        override fun onStatus(status: ConnectStatus) { statuses += status }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun serve(answerPing: Boolean = true, welcomeOnHello: Boolean = true) {
        server.enqueue(MockResponse().withWebSocketUpgrade(roomListener(answerPing, welcomeOnHello)))
        repeat(6) { server.enqueue(MockResponse().withWebSocketUpgrade(roomListener(answerPing, welcomeOnHello))) }
    }

    private fun roomListener(answerPing: Boolean, welcomeOnHello: Boolean): WebSocketListener {
        val number = ++connections
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets += webSocket
                openers += response.request.headers
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                frames += number to text
                if (text == "ping" && answerPing) webSocket.send("pong")
                if (text.startsWith("{\"type\":\"hello\"") && welcomeOnHello) webSocket.send(welcome)
            }
        }
    }

    private fun client(listener: Listener, keepaliveMs: Long = 60_000, deadAfterMs: Long = 120_000) = ConnectClient(
        serverUrl = server.url("/").toString(),
        room = "r".repeat(32),
        device = device,
        listener = listener,
        scope = scope,
        keepaliveMs = keepaliveMs,
        deadAfterMs = deadAfterMs,
        backoffMs = listOf(30L, 30L),
        random = { 0.5 },
    )

    private fun next() = frames.poll(5, TimeUnit.SECONDS)

    @Test
    fun connects_toTheRoomPath_withNoOrigin_andSaysHelloFirst() {
        serve()
        val listener = Listener()
        val c = client(listener)
        c.start()
        val first = next()!!
        assertEquals(1, first.first)
        assertEquals(ClientMessage.Hello(device = device), ConnectCodec.parseClient(first.second))
        assertEquals("/ws/" + "r".repeat(32), server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertNull(openers.first()["Origin"])
        assertTrue(listener.messages.poll(5, TimeUnit.SECONDS) is ServerMessage.Welcome)
        assertTrue(ConnectStatus.ONLINE in listener.statuses)
        c.stop()
        assertEquals(ConnectStatus.OFF, listener.statuses.last())
    }

    @Test
    fun send_goesOutOnceOpen_andNotBefore() {
        serve()
        val listener = Listener()
        val c = client(listener)
        assertFalse(c.send(ClientMessage.Ping(1)))
        c.start()
        listener.messages.poll(5, TimeUnit.SECONDS)
        next() // hello
        assertTrue(c.send(ClientMessage.Transfer("x", true)))
        assertEquals("""{"type":"transfer","to":"x","play":true}""", next()!!.second)
        c.stop()
    }

    @Test
    fun keepAlive_isThePlainTextPing_andAnyAnswerCountsAsLife() {
        serve()
        val listener = Listener()
        val c = client(listener, keepaliveMs = 40, deadAfterMs = 10_000)
        c.start()
        val texts = (1..4).map { next()!!.second }
        assertEquals("ping", texts[1])
        assertEquals("ping", texts[2])
        // The pong is not a message to anybody else.
        assertTrue(listener.messages.none { it !is ServerMessage.Welcome })
        c.stop()
    }

    @Test
    fun aSocketThatGoesQuiet_isDeclaredDead_andReopened() {
        serve(answerPing = false)
        val listener = Listener()
        val c = client(listener, keepaliveMs = 40, deadAfterMs = 150)
        c.start()
        var reconnected = false
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && !reconnected) {
            val f = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (f.first == 2) reconnected = true
        }
        assertTrue("a second connection was made after the silence", reconnected)
        c.stop()
    }

    @Test
    fun aClosedSocket_isReopenedAfterTheBackoff_andSaysHelloAgain() {
        serve()
        val listener = Listener()
        val c = client(listener)
        c.start()
        next() // hello #1
        sockets.first().close(1001, "going away")
        var hello2: Pair<Int, String>? = null
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && hello2 == null) {
            hello2 = frames.poll(200, TimeUnit.MILLISECONDS)?.takeIf { it.first == 2 }
        }
        assertEquals(ClientMessage.Hello(device = device), ConnectCodec.parseClient(hello2!!.second))
        assertTrue(ConnectStatus.CONNECTING in listener.statuses)
        c.stop()
    }

    @Test
    fun aProtocolError_endsTheLoop_insteadOfRetryingForever() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"error","code":"protocol"}""")
                    webSocket.close(1000, "bye")
                }
            }),
        )
        val listener = Listener()
        val c = client(listener)
        c.start()
        assertEquals(ServerMessage.Error("protocol"), listener.messages.poll(5, TimeUnit.SECONDS))
        Thread.sleep(400)
        assertEquals("no second attempt", 1, server.requestCount)
        c.stop()
    }
}
