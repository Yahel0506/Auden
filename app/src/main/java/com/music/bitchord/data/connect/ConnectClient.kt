package com.music.bitchord.data.connect

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class ConnectStatus { OFF, CONNECTING, ONLINE }

/** The socket as the manager sees it, so a test can stand a fake in its place. */
interface ConnectTransport {
    fun start()
    fun stop()

    /** False when the socket is not open: the frame is dropped, nothing is queued (the next state replaces it). */
    fun send(message: ClientMessage): Boolean

    /** The network came back or the app woke: skip the rest of the backoff. */
    fun nudge() = Unit
}

interface ConnectTransportListener {
    fun onMessage(message: ServerMessage)
    fun onStatus(status: ConnectStatus)
}

fun interface ConnectTransportFactory {
    fun create(room: String, device: ConnectDevice, listener: ConnectTransportListener): ConnectTransport
}

/**
 * The WebSocket to the account's Connect room: opens, says hello, keeps itself alive and reconnects.
 *
 * What it does is what `web/app/src/auden/connect/client.ts` does, so the two behave alike against one
 * server. The plain text frame `ping` goes out every 25 s and anything heard (the `pong` included) counts as
 * life; 70 s of silence and the socket is dropped whatever it claims. A dropped socket is reopened after
 * 1, 2, 4, 8, 15, then 30 s (±20% jitter, so a crowd that lost the network together does not come back
 * together) and straight away when the network returns. A `protocol` or `too_many_devices` error ends the
 * loop: asking again would be answered the same way.
 *
 * The native app sends no Origin header, which is what the Worker allows for a non-browser client.
 */
class ConnectClient(
    /** `https://host` of the Connect Worker (an `http`/`ws` scheme is mapped to the socket one). */
    private val serverUrl: String,
    private val room: String,
    private val device: ConnectDevice,
    private val listener: ConnectTransportListener,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = defaultHttp,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Random.Default::nextDouble,
    private val keepaliveMs: Long = KEEPALIVE_MS,
    private val deadAfterMs: Long = DEAD_AFTER_MS,
    private val backoffMs: List<Long> = BACKOFF_MS,
) : ConnectTransport {

    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lastFrameAt = 0L
    @Volatile private var fatal = false
    @Volatile private var generation = 0
    private val nudges = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        fatal = false
        job = scope.launch { loop() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        generation++
        val s = socket
        socket = null
        open = false
        runCatching { s?.close(1000, "stop") }
        listener.onStatus(ConnectStatus.OFF)
    }

    override fun send(message: ClientMessage): Boolean {
        val s = socket ?: return false
        if (!open) return false
        return s.send(ConnectCodec.encode(message))
    }

    override fun nudge() {
        nudges.get()?.complete(Unit)
    }

    private suspend fun loop() {
        var attempt = 0
        while (currentCoroutineContext().isActive && !fatal) {
            listener.onStatus(ConnectStatus.CONNECTING)
            val welcomed = runOnce()
            if (fatal || !currentCoroutineContext().isActive) break
            listener.onStatus(ConnectStatus.CONNECTING)
            if (welcomed) attempt = 0
            val base = backoffMs[minOf(attempt++, backoffMs.size - 1)]
            val wait = (base * (0.8 + random() * 0.4)).toLong()
            val wake = CompletableDeferred<Unit>().also { nudges.set(it) }
            if (withTimeoutOrNull(wait) { wake.await() } != null) attempt = 0
            nudges.set(null)
        }
    }

    /** One connection, from open to close. True when the room welcomed us (so the backoff may restart). */
    private suspend fun runOnce(): Boolean {
        val closed = CompletableDeferred<Unit>()
        var welcomed = false
        val request = Request.Builder().url(wsUrl()).build()
        val gen = ++generation
        lastFrameAt = nowMs()
        val ws = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (gen != generation) return
                    open = true
                    lastFrameAt = nowMs()
                    webSocket.send(ConnectCodec.encode(ClientMessage.Hello(device = device)))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (gen != generation) return
                    lastFrameAt = nowMs()
                    if (text == ConnectCodec.KEEPALIVE_ANSWER) return
                    val message = ConnectCodec.parseServer(text) ?: return
                    if (message is ServerMessage.Welcome) {
                        welcomed = true
                        listener.onStatus(ConnectStatus.ONLINE)
                    }
                    if (message is ServerMessage.Error && (message.code == "protocol" || message.code == "too_many_devices")) {
                        fatal = true
                    }
                    listener.onMessage(message)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (gen == generation) closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "connect socket dropped: ${t.javaClass.simpleName}")
                    if (gen == generation) closed.complete(Unit)
                }
            },
        )
        socket = ws
        try {
            coroutineScope {
                val keepalive = launch {
                    while (true) {
                        delay(keepaliveMs)
                        if (nowMs() - lastFrameAt > deadAfterMs) {
                            // Dead whatever the socket says: cancel is immediate, close() waits for a handshake.
                            ws.cancel()
                            closed.complete(Unit)
                            return@launch
                        }
                        if (open) ws.send(ConnectCodec.KEEPALIVE)
                    }
                }
                closed.await()
                keepalive.cancel()
            }
        } finally {
            open = false
            if (socket === ws) socket = null
            runCatching { ws.cancel() }
        }
        return welcomed
    }

    private fun wsUrl(): String {
        val base = serverUrl.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base/ws/$room"
    }

    companion object {
        private const val TAG = "ConnectClient"
        const val KEEPALIVE_MS = 25_000L
        const val DEAD_AFTER_MS = 70_000L
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

        /** Not the shared media client: a room can sit quiet for minutes, so no read timeout; the keep-alive says if it is dead. */
        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
