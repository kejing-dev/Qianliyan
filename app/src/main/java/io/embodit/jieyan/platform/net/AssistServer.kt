package io.embodit.jieyan.platform.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.jieyan.platform.JieyanLog
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The headset's embedded server (networking.md scheme A): Ktor CIO on [PORT] serves the expert page
 * (`GET /`, from assets) and one WebSocket (`/ws`). At most one expert at a time; a second one is
 * rejected with `busy`. Everything the server tells the [Listener] arrives on the main thread.
 *
 * Sending: JSON messages go through an ordered channel; picture frames through a conflated channel, so
 * a slow link only ever sees the newest frame (never a queue of old ones).
 */
class AssistServer(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** `hello` arrived: answer with [Welcome] to accept or [Reject] to refuse. Main thread. */
        fun onHello(pin: String, session: String?): HelloResult

        /** A JSON message from the connected expert. Main thread. */
        fun onMessage(message: JSONObject)

        /** The expert's socket closed (or the expert was replaced). Main thread. */
        fun onExpertGone(session: String)
    }

    sealed class HelloResult {
        /** [state]: the full session state, sent right after the welcome on the same socket. */
        data class Welcome(val session: String, val welcome: JSONObject, val state: JSONObject? = null) : HelloResult()

        data class Reject(val reason: String) : HelloResult()
    }

    private inner class Expert(val session: String, val socket: DefaultWebSocketServerSession) {
        val text = Channel<String>(capacity = 256)
        val frames = Channel<ByteArray>(capacity = Channel.CONFLATED)
        val closed = CompletableDeferred<Unit>()
        var textJob: Job? = null
        var frameJob: Job? = null
        var frameSeq = 0

        fun startSenders() {
            textJob =
                socket.launch {
                    try {
                        for (msg in text) socket.send(Frame.Text(msg))
                    } catch (_: Throwable) {
                    }
                }
            frameJob =
                socket.launch {
                    try {
                        for (bytes in frames) {
                            socket.send(Frame.Binary(true, bytes))
                            frameSeq++
                        }
                    } catch (_: Throwable) {
                    }
                }
        }

        fun stopSenders() {
            text.close()
            frames.close()
            textJob?.cancel()
            frameJob?.cancel()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @Volatile private var expert: Expert? = null
    private val frameSeq = AtomicInteger(0)
    private val html: String by lazy {
        runCatching { context.assets.open("expert.html").bufferedReader(Charsets.UTF_8).readText() }
            .getOrElse { "<html><body><h1>Jieyan</h1><p>expert.html missing: $it</p></body></html>" }
    }

    val running: Boolean
        get() = engine != null

    /** True while an expert socket is open and welcomed. */
    val connected: Boolean
        get() = expert != null

    fun start() {
        if (engine != null) return
        val e =
            embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                install(WebSockets) { maxFrameSize = Long.MAX_VALUE; masking = false }
                routing {
                    get("/") { call.respondText(html, ContentType.Text.Html) }
                    get("/health") { call.respondText("ok", ContentType.Text.Plain) }
                    webSocket("/ws") { serve(this) }
                }
            }
        engine = e
        Thread {
            try {
                e.start(wait = false)
                JieyanLog.i("SERVER started port=$PORT")
            } catch (t: Throwable) {
                JieyanLog.e("SERVER start failed: $t", t)
                engine = null
            }
        }.apply { name = "jieyan-server-start" }.start()
    }

    fun stop() {
        val e = engine ?: return
        engine = null
        expert?.let { x ->
            x.stopSenders()
            runCatching { x.socket.launch { x.socket.close(CloseReason(CloseReason.Codes.GOING_AWAY, "server stopped")) } }
        }
        expert = null
        Thread {
            runCatching { e.stop(200, 800) }.onFailure { JieyanLog.w("SERVER stop: $it") }
            JieyanLog.i("SERVER stopped")
        }.apply { name = "jieyan-server-stop" }.start()
    }

    /** Sends a JSON message to the expert (no-op without one). Any thread. */
    fun send(message: JSONObject) {
        val x = expert ?: return
        x.text.trySend(message.toString())
    }

    /** Sends a picture frame; only the newest one is kept when the link is slow. Any thread. */
    fun sendFrame(tsMs: Long, jpeg: ByteArray) {
        val x = expert ?: return
        val seq = frameSeq.incrementAndGet()
        x.frames.trySend(io.embodit.jieyan.domain.Protocol.frame(seq, tsMs, jpeg))
    }

    /** Closes the expert's socket (session ended by the worker). */
    fun dropExpert(reason: String) {
        val x = expert ?: return
        expert = null
        x.stopSenders()
        x.socket.launch { runCatching { x.socket.close(CloseReason(CloseReason.Codes.NORMAL, reason)) } }
        JieyanLog.i("WS dropped session=${x.session} reason=$reason")
    }

    private suspend fun serve(socket: DefaultWebSocketServerSession) {
        var me: Expert? = null
        try {
            for (frame in socket.incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue
                val type = msg.optString("t")
                if (me == null) {
                    if (type != "hello") continue
                    val pin = msg.optString("pin")
                    val session = msg.optString("session").takeIf { it.isNotEmpty() }
                    val busy = expert != null && expert?.session != session
                    val result = if (busy) HelloResult.Reject("busy") else withContext(Dispatchers.Main) { listener.onHello(pin, session) }
                    when (result) {
                        is HelloResult.Reject -> {
                            JieyanLog.i("WS hello rejected reason=${result.reason}")
                            socket.send(Frame.Text(io.embodit.jieyan.domain.Protocol.reject(result.reason).toString()))
                            if (result.reason == "busy") {
                                socket.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
                                return
                            }
                        }
                        is HelloResult.Welcome -> {
                            expert?.let { old ->
                                // the same session coming back on a new socket: the old socket is let go
                                old.stopSenders()
                                old.socket.launch { runCatching { old.socket.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) } }
                            }
                            me = Expert(result.session, socket).also { it.startSenders() }
                            expert = me
                            socket.send(Frame.Text(result.welcome.toString()))
                            result.state?.let { socket.send(Frame.Text(it.toString())) }
                            JieyanLog.i("WS welcome session=${result.session}")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { listener.onMessage(msg) }
                }
            }
        } catch (_: ClosedReceiveChannelException) {
        } catch (t: Throwable) {
            if (socket.isActive) JieyanLog.w("WS error: $t")
        } finally {
            val mine = me
            if (mine != null) {
                mine.stopSenders()
                if (expert === mine) {
                    expert = null
                    val session = mine.session
                    main.post { listener.onExpertGone(session) }
                }
                JieyanLog.i("WS closed session=${mine.session} frames=${mine.frameSeq}")
            }
        }
    }

    companion object {
        const val PORT = 8765
    }
}
