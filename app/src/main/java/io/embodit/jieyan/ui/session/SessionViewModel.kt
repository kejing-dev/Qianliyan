package io.embodit.jieyan.ui.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.embodit.jieyan.domain.DataState
import io.embodit.jieyan.domain.Owner
import io.embodit.jieyan.domain.Phase
import io.embodit.jieyan.domain.Pin
import io.embodit.jieyan.domain.Pointer
import io.embodit.jieyan.domain.Protocol
import io.embodit.jieyan.domain.Stroke
import io.embodit.jieyan.domain.StrokePoint
import io.embodit.jieyan.platform.CameraPermission
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.platform.camera.CameraSource
import io.embodit.jieyan.platform.camera.CameraSources
import io.embodit.jieyan.platform.net.AssistServer
import io.embodit.jieyan.platform.net.NetworkAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * The session: the state machine of the design (S1 - S9) and the one place that talks to the server
 * and the camera. The headset is the authority; the expert page mirrors `state`.
 *
 * Main thread only (the server and the camera post to it). Every timer and every listener started in
 * [start] is stopped in [stop] / [onCleared].
 */
class SessionViewModel(private val context: Context) : ViewModel(), AssistServer.Listener, CameraSource.Listener {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    private val main = Handler(Looper.getMainLooper())
    private val server = AssistServer(context, this)
    private var camera: CameraSource? = null
    private var started = false

    /** Session id the expert reconnects with; a new one per welcome. */
    private var session: String? = null
    private var wrongPins = 0
    private var reconnectSinceMs = 0L
    private var stickyData: DataState? = null

    /** Last frame time (camera thread writes, main thread reads for the stale check). */
    @Volatile private var lastFrameMs = 0L

    /** Whether frames go out right now (read on the camera thread). */
    @Volatile private var streaming = false

    /** What the worker's clear took away, for the expert page's "撤销" on its snackbar (8 s). */
    private var lastCleared: List<Stroke> = emptyList()
    private var lastClearedAtMs = 0L

    /** The expert's strokes under way (point messages append to them). */
    private val openStrokes = HashMap<String, Stroke>()

    private var liveStrokeId: String? = null
    private var liveSentPoints = 0
    private var fakeExpert = false

    private val ui: SessionUiState
        get() = _state.value

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    /** Starts the server and the camera; the lobby shows a fresh PIN. Idempotent. */
    fun start() {
        if (started) return
        started = true
        val cam = CameraSources.create(context, preferVst = true)
        camera = cam
        val granted = cam.name == "synthetic" || CameraPermission.granted(context)
        CameraPermission.listener = { ok -> main.post { onPermission(ok) } }
        update {
            copy(
                pin = Pin.random(),
                address = NetworkAddress.lanIp()?.let { "http://$it:${AssistServer.PORT}" },
                permissionGranted = granted,
                intrinsics = cam.intrinsics,
                cameraName = cam.name,
            )
        }
        server.start()
        cam.start(this)
        main.postDelayed(staleCheck, STALE_CHECK_MS)
        main.postDelayed(addressCheck, ADDRESS_CHECK_MS)
        logState("start")
    }

    /** Stops everything: readback, socket, server, strokes (design lifecycle rule). */
    fun stop() {
        if (!started) return
        started = false
        streaming = false
        main.removeCallbacksAndMessages(null)
        CameraPermission.listener = null
        server.send(Protocol.end("closed"))
        server.dropExpert("closed")
        server.stop()
        camera?.stop()
        camera = null
        openStrokes.clear()
        session = null
        fakeExpert = false
        update { SessionUiState(pin = pin, address = address, permissionGranted = permissionGranted, intrinsics = intrinsics, cameraName = cameraName) }
        logState("stop")
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    // =========================================================================================
    // events from the panels
    // =========================================================================================

    fun onEvent(event: SessionEvent) {
        when (event) {
            SessionEvent.PenToggled -> setPen(!ui.penOn)
            SessionEvent.UndoClicked -> if (ui.canUndo) undo(Owner.WORKER)
            SessionEvent.ClearClicked -> if (ui.canClear) clear(Owner.WORKER)
            SessionEvent.ShareToggled -> setShare(!ui.shareOn)
            SessionEvent.EndClicked -> if (ui.stripShown) update { copy(confirmingEnd = true) }.also { logState("confirm") }
            SessionEvent.EndCancelled -> update { copy(confirmingEnd = false) }.also { logState("confirm-no") }
            SessionEvent.EndConfirmed -> endSession("worker")
            SessionEvent.GrantClicked -> CameraPermission.request(context)
            is SessionEvent.GlassStrokeBegin -> beginLiveStroke(event.at)
            is SessionEvent.GlassStrokePoint -> addLivePoint(event.at)
            SessionEvent.GlassStrokeEnd -> endLiveStroke()
            is SessionEvent.DebugState -> debugState(event.name)
            is SessionEvent.DebugExpert -> if (event.connected) debugConnectExpert() else onExpertGone(session ?: "debug")
            is SessionEvent.DebugStroke -> debugStroke(event)
            is SessionEvent.DebugPointer -> setPointer(event.at)
            is SessionEvent.DebugData -> {
                stickyData = if (event.data == DataState.NORMAL) null else event.data
                setData(event.data)
            }
            is SessionEvent.DebugReconnect ->
                when {
                    event.timeout -> reconnectTimedOut()
                    event.on -> onExpertGone(session ?: "debug")
                    else -> if (ui.phase == Phase.RECONNECTING) resumeAfterReconnect()
                }
            is SessionEvent.DebugPermission -> onPermission(event.granted)
            is SessionEvent.DebugFade -> setFade(event.on)
            is SessionEvent.DebugPen -> setPen(event.on)
            is SessionEvent.DebugShare -> setShare(event.on)
        }
    }

    // =========================================================================================
    // the state machine
    // =========================================================================================

    private fun setPen(on: Boolean) {
        if (on && !ui.drawKeysEnabled) return
        if (on == ui.penOn) return
        if (!on) endLiveStroke()
        update { copy(penOn = on) }
        server.send(Protocol.pen(on))
        logState(if (on) "pen-on" else "pen-off")
    }

    private fun setShare(on: Boolean) {
        when (ui.phase) {
            Phase.LIVE -> if (!on) {
                endLiveStroke()
                streaming = false
                update { copy(phase = Phase.PAUSED, penOn = false, confirmingEnd = false) }
                sendState()
                logState("paused")
            }
            Phase.PAUSED -> if (on) {
                update { copy(phase = Phase.LIVE, confirmingEnd = false) }
                streaming = true
                lastFrameMs = SystemClock.elapsedRealtime()
                sendState()
                logState("resumed")
            }
            else -> Unit
        }
    }

    private fun setData(data: DataState) {
        if (ui.data == data) return
        // no picture, no pen (sample 4.5: the pen key is off in S8)
        if (data == DataState.ERROR && ui.penOn) setPen(false)
        update { copy(data = data) }
        sendState()
        logState("data-${data.wire}")
    }

    private fun setFade(on: Boolean) {
        if (ui.fadeOn == on) return
        update { copy(fadeOn = on) }
        server.send(Protocol.fade(on))
        scheduleFadeTick()
        logState(if (on) "fade-on" else "fade-off")
    }

    private fun undo(owner: Owner) {
        val last = ui.strokes.lastOrNull { it.owner == owner } ?: return
        update { copy(strokes = strokes.filter { it.id != last.id }) }
        server.send(Protocol.undo(last.id, owner))
        JieyanLog.i("STROKE undo id=${last.id} owner=${owner.wire} left=${ui.strokes.size}")
    }

    private fun clear(by: Owner) {
        endLiveStroke()
        lastCleared = ui.strokes
        lastClearedAtMs = SystemClock.elapsedRealtime()
        update { copy(strokes = emptyList()) }
        openStrokes.clear()
        server.send(Protocol.clear(by))
        JieyanLog.i("STROKE clear by=${by.wire} n=${lastCleared.size}")
    }

    private fun unclear() {
        if (lastCleared.isEmpty() || SystemClock.elapsedRealtime() - lastClearedAtMs > UNCLEAR_WINDOW_MS) return
        val restored = lastCleared
        lastCleared = emptyList()
        update { copy(strokes = restored + strokes) }
        server.send(Protocol.unclear(restored))
        scheduleFadeTick()
        JieyanLog.i("STROKE unclear n=${restored.size}")
    }

    private fun setPointer(at: StrokePoint?) {
        main.removeCallbacks(pointerExpiry)
        if (at == null) {
            if (ui.pointer != null) update { copy(pointer = null) }
            return
        }
        update { copy(pointer = Pointer(at.x, at.y, SystemClock.elapsedRealtime())) }
        main.postDelayed(pointerExpiry, Pointer.LIFETIME_MS)
    }

    private val pointerExpiry = Runnable { if (ui.pointer != null) update { copy(pointer = null) } }

    /** T10: back to the lobby with a new PIN; the expert page is told and sees "会话已结束". */
    private fun endSession(by: String) {
        endLiveStroke()
        streaming = false
        main.removeCallbacks(reconnectTimeout)
        server.send(Protocol.end(by))
        server.dropExpert("ended by $by")
        session = null
        fakeExpert = false
        openStrokes.clear()
        update {
            copy(
                phase = Phase.LOBBY,
                phaseBefore = Phase.LIVE,
                pin = Pin.random(),
                expertConnected = false,
                penOn = false,
                strokes = emptyList(),
                liveStroke = null,
                pointer = null,
                confirmingEnd = false,
                data = DataState.NORMAL,
            )
        }
        stickyData = null
        logState("ended by=$by")
    }

    private fun onPermission(granted: Boolean) {
        update { copy(permissionGranted = granted) }
        logState(if (granted) "perm-granted" else "perm-denied")
    }

    // ---- reconnect (T9)

    override fun onExpertGone(session: String) {
        if (this.session != session && !fakeExpert) return
        if (ui.phase == Phase.LOBBY || ui.phase == Phase.RECONNECTING) return
        endLiveStroke()
        streaming = false
        reconnectSinceMs = SystemClock.elapsedRealtime()
        update { copy(phase = Phase.RECONNECTING, phaseBefore = phase, penOn = false, pointer = null, confirmingEnd = false) }
        main.removeCallbacks(reconnectTimeout)
        main.postDelayed(reconnectTimeout, RECONNECT_TIMEOUT_MS)
        logState("reconnecting")
    }

    private val reconnectTimeout = Runnable { reconnectTimedOut() }

    private fun reconnectTimedOut() {
        if (ui.phase != Phase.RECONNECTING) return
        JieyanLog.i("WS reconnect timed out after ${SystemClock.elapsedRealtime() - reconnectSinceMs} ms")
        endSession("timeout")
    }

    private fun resumeAfterReconnect() {
        main.removeCallbacks(reconnectTimeout)
        val back = ui.phaseBefore
        update { copy(phase = back, expertConnected = true) }
        streaming = back == Phase.LIVE
        lastFrameMs = SystemClock.elapsedRealtime()
        sendState()
        logState("reconnected to=${back.name.lowercase()}")
    }

    // =========================================================================================
    // server
    // =========================================================================================

    override fun onHello(pin: String, session: String?): AssistServer.HelloResult {
        val cam = camera
        val intrinsics = ui.intrinsics
        // a known session coming back within the reconnect window
        if (session != null && session == this.session && ui.phase == Phase.RECONNECTING) {
            resumeAfterReconnect()
            return AssistServer.HelloResult.Welcome(session, Protocol.welcome(session, intrinsics, cam?.targetFps ?: 15), stateJson())
        }
        if (ui.pinLocked) return AssistServer.HelloResult.Reject("locked")
        if (ui.phase != Phase.LOBBY && !ui.lobbyShown) return AssistServer.HelloResult.Reject("busy")
        if (pin != ui.pin) {
            wrongPins++
            JieyanLog.i("WS pin wrong tries=$wrongPins")
            if (wrongPins >= PIN_TRIES) {
                wrongPins = 0
                update { copy(pinLocked = true) }
                main.postDelayed({ update { copy(pinLocked = false) } }, PIN_LOCK_MS)
                logState("pin-locked")
                return AssistServer.HelloResult.Reject("locked")
            }
            return AssistServer.HelloResult.Reject("pin")
        }
        wrongPins = 0
        val id = UUID.randomUUID().toString().substring(0, 8)
        this.session = id
        fakeExpert = false
        goLive()
        // the full state travels right behind the welcome on the new socket (a send() here would race the socket's registration)
        return AssistServer.HelloResult.Welcome(id, Protocol.welcome(id, intrinsics, cam?.targetFps ?: 15), stateJson())
    }

    /** T1: lobby -> live. */
    private fun goLive() {
        main.removeCallbacks(reconnectTimeout)
        update { copy(phase = Phase.LIVE, phaseBefore = Phase.LIVE, expertConnected = true, penOn = false, confirmingEnd = false, data = stickyData ?: DataState.NORMAL) }
        streaming = true
        lastFrameMs = SystemClock.elapsedRealtime()
        logState("live")
    }

    override fun onMessage(message: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        when (message.optString("t")) {
            "stroke.begin" -> {
                val stroke = Protocol.parseStroke(message, now).copy(owner = Owner.EXPERT, endedAtMs = null)
                openStrokes[stroke.id] = stroke
                update { copy(strokes = strokes.filter { it.id != stroke.id } + stroke) }
            }
            "stroke.point" -> {
                val id = message.optString("id")
                val pts = Protocol.parsePoints(message.optJSONArray("pts"))
                val open = openStrokes[id] ?: return
                val grown = open.copy(points = open.points + pts)
                openStrokes[id] = grown
                update { copy(strokes = strokes.map { if (it.id == id) grown else it }) }
            }
            "stroke.end" -> {
                val id = message.optString("id")
                val open = openStrokes.remove(id) ?: return
                val done = open.copy(endedAtMs = now)
                update { copy(strokes = strokes.map { if (it.id == id) done else it }) }
                scheduleFadeTick()
                JieyanLog.i("STROKE expert id=$id pts=${done.points.size} color=${done.color} width=${done.width}")
            }
            "undo" -> undo(Owner.EXPERT)
            "clear" -> clear(Owner.EXPERT)
            "unclear" -> unclear()
            "pointer" -> setPointer(StrokePoint(message.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f), message.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)))
            "fade" -> setFade(message.optBoolean("on", false))
            "end" -> endSession("expert")
            "ping" -> server.send(Protocol.pong(message.optLong("ts", 0L)))
        }
    }

    private fun stateJson(): JSONObject {
        val s = ui
        return Protocol.state(s.phase, s.shareOn, s.penOn, s.data, s.fadeOn, s.strokes)
    }

    private fun sendState() {
        server.send(stateJson())
    }

    // =========================================================================================
    // camera
    // =========================================================================================

    override fun onFrame(jpeg: ByteArray, tsMs: Long) {
        lastFrameMs = tsMs
        if (!streaming) return
        server.sendFrame(tsMs, jpeg)
    }

    override fun onError(reason: String) {
        main.post {
            JieyanLog.w("CAM error: $reason")
            if (ui.phase == Phase.LIVE) setData(DataState.ERROR)
        }
    }

    private val staleCheck =
        object : Runnable {
            override fun run() {
                if (!started) return
                if (ui.phase == Phase.LIVE && stickyData == null) {
                    val stale = SystemClock.elapsedRealtime() - lastFrameMs > STALE_AFTER_MS
                    if (ui.data != DataState.ERROR) setData(if (stale) DataState.STALE else DataState.NORMAL)
                }
                main.postDelayed(this, STALE_CHECK_MS)
            }
        }

    private val addressCheck =
        object : Runnable {
            override fun run() {
                if (!started) return
                val address = NetworkAddress.lanIp()?.let { "http://$it:${AssistServer.PORT}" }
                if (address != ui.address) {
                    update { copy(address = address) }
                    JieyanLog.i("SERVER address=$address")
                }
                main.postDelayed(this, ADDRESS_CHECK_MS)
            }
        }

    // =========================================================================================
    // the worker's pen (glass)
    // =========================================================================================

    private fun beginLiveStroke(at: StrokePoint) {
        if (!ui.penOn || ui.phase != Phase.LIVE) return
        endLiveStroke()
        val id = "w${SystemClock.elapsedRealtime().toString(36)}"
        liveStrokeId = id
        liveSentPoints = 1
        val stroke = Stroke(id = id, owner = Owner.WORKER, color = WORKER_COLOR, width = WORKER_WIDTH, points = listOf(at))
        update { copy(liveStroke = stroke) }
        server.send(Protocol.strokeBegin(stroke))
        main.postDelayed(liveFlush, LIVE_FLUSH_MS)
    }

    private fun addLivePoint(at: StrokePoint) {
        val live = ui.liveStroke ?: return
        update { copy(liveStroke = live.copy(points = live.points + at)) }
    }

    /** Points of the worker's stroke go out in batches every 33 ms (networking.md §5). */
    private val liveFlush =
        object : Runnable {
            override fun run() {
                val live = ui.liveStroke ?: return
                if (live.points.size > liveSentPoints) {
                    server.send(Protocol.strokePoint(live.id, live.points.subList(liveSentPoints, live.points.size)))
                    liveSentPoints = live.points.size
                }
                main.postDelayed(this, LIVE_FLUSH_MS)
            }
        }

    private fun endLiveStroke() {
        main.removeCallbacks(liveFlush)
        val live = ui.liveStroke ?: return
        if (live.points.size > liveSentPoints) {
            server.send(Protocol.strokePoint(live.id, live.points.subList(liveSentPoints, live.points.size)))
        }
        val done = live.copy(endedAtMs = SystemClock.elapsedRealtime())
        update { copy(liveStroke = null, strokes = strokes + done) }
        server.send(Protocol.strokeEnd(live.id))
        liveStrokeId = null
        scheduleFadeTick()
        JieyanLog.i("STROKE worker id=${live.id} pts=${live.points.size}")
    }

    // ---- fade (T7): a tick every 100 ms while any stroke is fading; strokes that are gone are dropped

    private val fadeTicker =
        object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val s = ui
                if (!s.fadeOn || s.strokes.none { it.endedAtMs != null }) return
                val alive = s.strokes.filter { it.alpha(now, true) > 0f }
                update { copy(strokes = alive, fadeTick = now) }
                if (alive.any { it.endedAtMs != null }) main.postDelayed(this, FADE_TICK_MS)
            }
        }

    private fun scheduleFadeTick() {
        main.removeCallbacks(fadeTicker)
        if (ui.fadeOn) main.postDelayed(fadeTicker, FADE_TICK_MS)
    }

    // =========================================================================================
    // debug hooks (the emulator never reaches some designed states by itself)
    // =========================================================================================

    private fun debugConnectExpert() {
        if (session == null) session = "debug"
        fakeExpert = true
        if (ui.phase == Phase.RECONNECTING) resumeAfterReconnect() else goLive()
    }

    private fun debugStroke(event: SessionEvent.DebugStroke) {
        val now = SystemClock.elapsedRealtime()
        val stroke =
            Stroke(
                id = "d${now.toString(36)}",
                owner = event.owner,
                color = event.color ?: if (event.owner == Owner.WORKER) WORKER_COLOR else "#FFE14D",
                width = event.width ?: if (event.owner == Owner.WORKER) WORKER_WIDTH else 6f,
                points = event.points,
                endedAtMs = now,
            )
        update { copy(strokes = strokes + stroke) }
        if (event.owner == Owner.WORKER) {
            server.send(Protocol.strokeBegin(stroke))
            server.send(Protocol.strokeEnd(stroke.id))
        }
        scheduleFadeTick()
        JieyanLog.i("STROKE debug id=${stroke.id} owner=${stroke.owner.wire} pts=${stroke.points.size}")
    }

    /** Puts the app in a named design state (S1 - S9) with a simulated expert where one is needed. */
    private fun debugState(name: String) {
        when (name.uppercase(Locale.US)) {
            "S1" -> {
                endSession("debug")
                onPermission(true)
            }
            "S2" -> {
                endSession("debug")
                onPermission(false)
            }
            "S3" -> {
                liveBase()
                setPen(false)
            }
            "S4" -> {
                liveBase()
                setPen(true)
            }
            "S5" -> {
                liveBase()
                setPen(false)
                setShare(false)
            }
            "S6" -> {
                liveBase()
                onExpertGone(session ?: "debug")
            }
            "S7" -> {
                liveBase()
                setPen(false)
                stickyData = DataState.STALE
                setData(DataState.STALE)
            }
            "S8" -> {
                liveBase()
                stickyData = DataState.ERROR
                setData(DataState.ERROR)
            }
            "S9" -> {
                liveBase()
                setPen(false)
                update { copy(confirmingEnd = true) }
            }
            else -> JieyanLog.w("CMD state unknown '$name'")
        }
        logState("debug-$name")
    }

    /**
     * Every debug state S3 - S9 starts from the same base: live, picture normal, no confirmation. Without
     * it a jump from S8 to S4 stayed in S8 (no picture, no pen).
     */
    private fun liveBase() {
        ensureLive()
        update { copy(confirmingEnd = false) }
        stickyData = null
        setData(DataState.NORMAL)
    }

    private fun ensureLive() {
        when (ui.phase) {
            Phase.LOBBY -> debugConnectExpert()
            Phase.RECONNECTING -> resumeAfterReconnect()
            Phase.PAUSED -> setShare(true)
            Phase.LIVE -> Unit
        }
        if (ui.phase == Phase.PAUSED) {
            // phaseBefore was PAUSED: bring it to live for the states that need it
            update { copy(phase = Phase.LIVE) }
            streaming = true
        }
    }

    /** One JSON line with the whole model, for the debug command `dump`. */
    fun dumpJson(): String {
        val s = ui
        val o = JSONObject()
        o.put("designState", s.designState)
        o.put("phase", s.phase.name.lowercase())
        o.put("phaseBefore", s.phaseBefore.name.lowercase())
        o.put("pin", s.pin)
        o.put("address", s.address)
        o.put("permission", s.permissionGranted)
        o.put("pinLocked", s.pinLocked)
        o.put("expert", s.expertConnected)
        o.put("expertSocket", server.connected)
        o.put("fakeExpert", fakeExpert)
        o.put("session", session)
        o.put("share", s.shareOn)
        o.put("pen", s.penOn)
        o.put("fade", s.fadeOn)
        o.put("data", s.data.wire)
        o.put("confirmingEnd", s.confirmingEnd)
        o.put("strokes", s.strokes.size)
        o.put("strokesExpert", s.strokes.count { it.owner == Owner.EXPERT })
        o.put("strokesWorker", s.strokes.count { it.owner == Owner.WORKER })
        o.put("liveStroke", s.liveStroke?.points?.size ?: 0)
        o.put("pointer", s.pointer?.let { JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()) })
        o.put("camera", s.cameraName)
        o.put("image", "${s.intrinsics.width}x${s.intrinsics.height}")
        o.put("glassM", String.format(Locale.US, "%.3fx%.3f", s.intrinsics.glassWidthM, s.intrinsics.glassHeightM))
        o.put("lastFrameAgoMs", if (lastFrameMs == 0L) -1 else SystemClock.elapsedRealtime() - lastFrameMs)
        o.put("streaming", streaming)
        o.put("serverRunning", server.running)
        return o.toString()
    }

    // =========================================================================================

    private inline fun update(block: SessionUiState.() -> SessionUiState) {
        _state.value = _state.value.block()
    }

    private fun logState(why: String) {
        val s = ui
        JieyanLog.i("STATE ${s.designState} phase=${s.phase.name.lowercase()} why=$why pin=${s.pin} expert=${s.expertConnected} pen=${s.penOn} data=${s.data.wire} strokes=${s.strokes.size}")
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(context.applicationContext) as T
    }

    companion object {
        const val WORKER_COLOR = "#8CD5FF"
        const val WORKER_WIDTH = 6f
        private const val STALE_AFTER_MS = 3_000L
        private const val STALE_CHECK_MS = 500L
        private const val ADDRESS_CHECK_MS = 5_000L
        private const val RECONNECT_TIMEOUT_MS = 30_000L
        private const val PIN_TRIES = 5
        private const val PIN_LOCK_MS = 60_000L
        private const val UNCLEAR_WINDOW_MS = 8_000L
        private const val LIVE_FLUSH_MS = 33L
        private const val FADE_TICK_MS = 100L
    }
}
