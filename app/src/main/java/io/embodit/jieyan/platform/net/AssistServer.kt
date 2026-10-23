package io.embodit.jieyan.platform.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.embodit.jieyan.platform.JieyanLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
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
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The headset's embedded server (networking.md scheme A, design v3 §2): Ktor CIO serves the expert page
 * (`GET /`, from assets), `GET /info`, and two WebSockets — `/ws` (JSON + picture frames) and `/audio`
 * (voice, kept apart so a 80 KB picture never holds up a 643-byte voice packet). At most one expert at a
 * time; a second one is rejected with `busy`. Everything the server tells the [Listener] arrives on the
 * main thread, except [Listener.onAudioPacket] (network thread, straight to the voice engine).
 *
 * Connectors: [PORT] on every interface (LAN http, and USB `adb forward` which arrives from 127.0.0.1),
 * and [TLS_BACKEND_PORT] on loopback only, fed by [TlsGateway] with the decrypted LAN https traffic.
 * [linkOf] tells the three apart: `usb` (loopback on [PORT]), `wifi` (LAN http or https).
 *
 * Sending: JSON messages go through an ordered channel; picture frames through a conflated channel
 * (only the newest frame); voice through a short drop-oldest queue (at most 200 ms).
 */
class AssistServer(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** `hello` arrived: answer with [Welcome] to accept or [Reject] to refuse. [link]: `usb` or `wifi`. Main thread. */
        fun onHello(pin: String, session: String?, link: String): HelloResult

        /** A JSON message from the connected expert. Main thread. */
        fun onMessage(message: JSONObject)

        /** The expert's socket closed (or the expert was replaced). Main thread. */
        fun onExpertGone(session: String)

        /** `/audio` opened (true) or closed (false) for the current session. Main thread. */
        fun onAudioLink(open: Boolean)

        /** One voice packet from the expert (`[u16 seq][u8 flags][PCM16 x 320]`). Network thread. */
        fun onAudioPacket(packet: ByteArray)
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

    private inner class Voice(val session: String, val socket: DefaultWebSocketServerSession) {
        val out = Channel<ByteArray>(capacity = AUDIO_QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var job: Job? = null
        var packetsIn = 0
        var packetsOut = 0

        fun start() {
            job =
                socket.launch {
                    try {
                        for (p in out) {
                            socket.send(Frame.Binary(true, p))
                            packetsOut++
                        }
                    } catch (_: Throwable) {
                    }
                }
        }

        fun stop() {
            out.close()
            job?.cancel()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @Volatile private var expert: Expert? = null
    @Volatile private var voice: Voice? = null
    private val frameSeq = AtomicInteger(0)
    private val html: String by lazy {
        runCatching { context.assets.open("expert.html").bufferedReader(Charsets.UTF_8).readText() }
            .getOrElse { "<html><body><h1>Jieyan</h1><p>expert.html missing: $it</p></body></html>" }
    }

    /** Whether the LAN https listener is up (the page offers E6 only then). Set by the session. */
    @Volatile var httpsAvailable: Boolean = false

    val running: Boolean
        get() = engine != null

    /** True while an expert socket is open and welcomed. */
    val connected: Boolean
        get() = expert != null

    /** True while the expert's `/audio` socket is open. */
    val audioConnected: Boolean
        get() = voice != null

    fun start() {
        if (engine != null) return
        val e =
            embeddedServer(
                CIO,
                configure = {
                    connector { host = "0.0.0.0"; port = PORT }
                    connector { host = "127.0.0.1"; port = TLS_BACKEND_PORT }
                },
            ) {
                install(WebSockets) { maxFrameSize = Long.MAX_VALUE; masking = false }
                routing {
                    get("/") { call.respondText(html, ContentType.Text.Html) }
                    get("/health") { call.respondText("ok", ContentType.Text.Plain) }
                    get("/info") {
                        val info = JSONObject().put("link", linkOf(call)).put("https", httpsAvailable).put("httpsPort", TlsGateway.PORT).put("secure", call.request.local.localPort == TLS_BACKEND_PORT)
                        call.respondText(info.toString(), ContentType.Application.Json)
                    }
                    get("/debug") {
                        val h = io.embodit.jieyan.platform.DebugBridge.handler
                        val cmd = call.request.queryParameters["cmd"]
                        if (h == null || cmd.isNullOrBlank()) {
                            call.respondText("no", ContentType.Text.Plain, HttpStatusCode.NotFound)
                        } else {
                            h(cmd, call.request.queryParameters["s"], call.request.queryParameters["a"])
                            call.respondText("ok $cmd", ContentType.Text.Plain)
                        }
                    }
                    webSocket("/ws") { serve(this, linkOf(call)) }
                    webSocket("/audio") { serveAudio(this, call.request.queryParameters["session"]) }
                }
            }
        engine = e
        Thread {
            try {
                e.start(wait = false)
                JieyanLog.i("SERVER started port=$PORT tlsBackend=$TLS_BACKEND_PORT")
            } catch (t: Throwable) {
                JieyanLog.e("SERVER start failed: $t", t)
                engine = null
            }
        }.apply { name = "jieyan-server-start" }.start()
    }

    fun stop() {
        val e = engine ?: return
        engine = null
        dropVoice("server stopped")
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

    /** Sends one voice packet to the expert (no-op without `/audio`). Any thread. */
    fun sendAudio(packet: ByteArray) {
        voice?.out?.trySend(packet)
    }

    /** Closes the expert's socket (session ended by the worker). */
    fun dropExpert(reason: String) {
        dropVoice(reason)
        val x = expert ?: return
        expert = null
        x.stopSenders()
        x.socket.launch { runCatching { x.socket.close(CloseReason(CloseReason.Codes.NORMAL, reason)) } }
        JieyanLog.i("WS dropped session=${x.session} reason=$reason")
    }

    /** Closes only the voice socket (hang-up, or the debug `drop audio`). */
    fun dropVoice(reason: String) {
        val v = voice ?: return
        voice = null
        v.stop()
        v.socket.launch { runCatching { v.socket.close(CloseReason(CloseReason.Codes.NORMAL, reason)) } }
        JieyanLog.i("AUDIO dropped session=${v.session} reason=$reason in=${v.packetsIn} out=${v.packetsOut}")
        main.post { listener.onAudioLink(false) }
    }

    private fun linkOf(call: ApplicationCall): String {
        val local = call.request.local
        return if (local.localPort == PORT && (local.remoteHost == "127.0.0.1" || local.remoteHost == "localhost" || local.remoteHost.startsWith("0:0:0:0:0:0:0:1"))) "usb" else "wifi"
    }

    private suspend fun serve(socket: DefaultWebSocketServerSession, link: String) {
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
                    val result = if (busy) HelloResult.Reject("busy") else withContext(Dispatchers.Main) { listener.onHello(pin, session, link) }
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
                            JieyanLog.i("WS welcome session=${result.session} link=$link")
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
                    dropVoice("ws closed")
                    val session = mine.session
                    main.post { listener.onExpertGone(session) }
                }
                JieyanLog.i("WS closed session=${mine.session} frames=${mine.frameSeq}")
            }
        }
    }

    /** `/audio?session=…`: accepted only for the welcomed expert's session (same gate as `/ws`). */
    private suspend fun serveAudio(socket: DefaultWebSocketServerSession, session: String?) {
        val x = expert
        if (x == null || session == null || session != x.session) {
            JieyanLog.i("AUDIO rejected session=$session")
            socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session"))
            return
        }
        voice?.let { old ->
            voice = null
            old.stop()
            old.socket.launch { runCatching { old.socket.close(CloseReason(CloseReason.Codes.NORMAL, "replaced")) } }
        }
        val me = Voice(session, socket).also { it.start() }
        voice = me
        JieyanLog.i("AUDIO open session=$session")
        main.post { listener.onAudioLink(true) }
        try {
            for (frame in socket.incoming) {
                if (frame !is Frame.Binary) continue
                me.packetsIn++
                listener.onAudioPacket(frame.readBytes())
            }
        } catch (_: ClosedReceiveChannelException) {
        } catch (t: Throwable) {
            if (socket.isActive) JieyanLog.w("AUDIO error: $t")
        } finally {
            me.stop()
            if (voice === me) {
                voice = null
                main.post { listener.onAudioLink(false) }
            }
            JieyanLog.i("AUDIO closed session=$session in=${me.packetsIn} out=${me.packetsOut}")
        }
    }

    companion object {
        const val PORT = 8765

        /** Loopback-only plain connector behind [TlsGateway]. */
        const val TLS_BACKEND_PORT = 8767

        /** 10 packets = 200 ms of voice, the design's jitter cap. */
        private const val AUDIO_QUEUE = 10
    }
}
