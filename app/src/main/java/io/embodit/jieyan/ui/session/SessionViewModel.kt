package io.embodit.jieyan.ui.session

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.domain.DataState
import io.embodit.jieyan.domain.ExpertMic
import io.embodit.jieyan.domain.Owner
import io.embodit.jieyan.domain.Phase
import io.embodit.jieyan.domain.Pin
import io.embodit.jieyan.domain.Pointer
import io.embodit.jieyan.domain.Protocol
import io.embodit.jieyan.domain.Stroke
import io.embodit.jieyan.domain.StrokePoint
import io.embodit.jieyan.domain.VideoError
import io.embodit.jieyan.platform.CameraPermission
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.platform.audio.VoiceEngine
import io.embodit.jieyan.platform.camera.CameraSource
import io.embodit.jieyan.platform.camera.CameraSources
import io.embodit.jieyan.platform.camera.SyntheticCameraSource
import io.embodit.jieyan.platform.camera.VstReadbackCameraSource
import io.embodit.jieyan.platform.camera.VstSize
import io.embodit.jieyan.platform.net.AssistServer
import io.embodit.jieyan.platform.net.NetworkAddress
import io.embodit.jieyan.platform.net.TlsGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The session: the state machine of the design (v2 S1 - S9 + v3 S10 - S15, S2m, S2s, S1u, S6a) and the
 * one place that talks to the server, the camera and the voice engine. The headset is the authority;
 * the expert page mirrors `state`.
 *
 * Picture (v3 C1 - C5): on a headset the VST readback runs only while it is needed — from the expert's
 * arrival (S10 until the first frame, 5 s limit), off while paused / reconnecting / after the end, and
 * rebuilt by the page's [重试] (`video.retry`). The lobby probes the VST size once (camera granted, no
 * expert yet). The emulator keeps its synthetic demo picture running.
 *
 * Voice (v3 C6 - C14): the engine runs from the expert's arrival to the end (not while reconnecting);
 * packets go out on `/audio` whenever that socket is open.
 *
 * Main thread only (the server, the camera and the voice engine post to it). Every timer and every
 * listener started in [start] is stopped in [stop] / [onCleared].
 */
class SessionViewModel(private val context: Context) :
    ViewModel(),
    AssistServer.Listener,
    CameraSource.Listener,
    VoiceEngine.Listener {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    /** Microphone levels at ~20 Hz; only the strip's mic key and wave read them (never the whole UI state). */
    data class Levels(val self: Float = 0f, val remote: Float = 0f, val selfSpeaking: Boolean = false, val remoteSpeaking: Boolean = false)

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels

    private val main = Handler(Looper.getMainLooper())
    private val server = AssistServer(context, this)
    private val tls = TlsGateway(context)
    private val voice = VoiceEngine(context, this)
    private var voiceOn = false
    private var camera: CameraSource? = null
    private var cameraRunning = false
    private var started = false

    /** Session id the expert reconnects with; a new one per welcome. */
    private var session: String? = null
    private var link = "wifi"
    private var wrongPins = 0
    private var reconnectSinceMs = 0L
    private var stickyData: DataState? = null
    private var stickyError: VideoError? = null

    /** Last frame time (camera thread writes, main thread reads for the stale check). */
    @Volatile private var lastFrameMs = 0L

    /** Whether frames go out right now (read on the camera thread). */
    @Volatile private var streaming = false

    /** Frames of the current second (camera thread counts, main thread reads once a second). */
    private val frameCount = AtomicInteger(0)
    private var lowFpsSeconds = 0
    private var veryLowFpsSeconds = 0

    /** What the worker's clear took away, for the expert page's "撤销" on its snackbar (8 s). */
    private var lastCleared: List<Stroke> = emptyList()
    private var lastClearedAtMs = 0L

    /** The expert's strokes under way (point messages append to them). */
    private val openStrokes = HashMap<String, Stroke>()

    private var liveStrokeId: String? = null
    private var liveSentPoints = 0
    private var fakeExpert = false

    /** Debug: an injected level ("speak") until this time. */
    @Volatile private var fakeSpeakUntilMs = 0L
    @Volatile private var fakeSpeaker = ""

    private var selfSpeakUntil = 0L
    private var remoteSpeakUntil = 0L
    private var selfAboveSince = 0L
    private var remoteAboveSince = 0L

    private val ui: SessionUiState
        get() = _state.value

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    /** Starts the server (http + https), the camera (emulator) and the permission request; the lobby shows a fresh PIN. Idempotent. */
    fun start() {
        if (started) return
        started = true
        val cam = CameraSources.create(context)
        camera = cam
        val perm = CameraPermission.current(context)
        val intrinsics = if (cam is VstReadbackCameraSource) VstSize.cachedIntrinsics(context) ?: cam.intrinsics else cam.intrinsics
        CameraPermission.listener = { r -> main.post { onPermission(r) } }
        val httpsOn = tls.start()
        server.httpsAvailable = httpsOn
        update {
            copy(
                pin = Pin.random(),
                address = NetworkAddress.lanIp()?.let { "$it:${AssistServer.PORT}" },
                permissionGranted = cam.name == "synthetic" || perm.camera,
                micGranted = perm.mic,
                cameraNeedsSettings = perm.cameraNever,
                micNeedsSettings = perm.micNever,
                httpsOn = httpsOn,
                intrinsics = intrinsics,
                cameraName = cam.name,
            )
        }
        server.start()
        if (cam.name == "synthetic") startCamera("emulator")
        main.postDelayed(staleCheck, STALE_CHECK_MS)
        main.postDelayed(addressCheck, ADDRESS_CHECK_MS)
        main.postDelayed(permissionCheck, PERMISSION_CHECK_MS)
        main.postDelayed(fpsTick, FPS_TICK_MS)
        // v3 C9: ask for camera + microphone at launch (once per run; the lobby rows ask again)
        if ((!perm.camera && !perm.cameraNever && cam.name != "synthetic") || (!perm.mic && !perm.micNever)) {
            CameraPermission.request(context)
        }
        // the probe waits for the Stage to settle (a VST session opened during launch failed to configure on the Swan)
        main.postDelayed({ probeVstSize() }, PROBE_DELAY_MS)
        logState("start")
    }

    /** Stops everything: readback, voice, sockets, servers, strokes (design lifecycle rule). */
    fun stop() {
        if (!started) return
        started = false
        streaming = false
        main.removeCallbacksAndMessages(null)
        CameraPermission.listener = null
        server.send(Protocol.end("closed"))
        server.dropExpert("closed")
        server.stop()
        tls.stop()
        stopVoice(chime = false)
        stopCamera("stop")
        probe?.stop()
        probe = null
        camera = null
        openStrokes.clear()
        session = null
        fakeExpert = false
        update {
            SessionUiState(
                pin = pin,
                address = address,
                permissionGranted = permissionGranted,
                micGranted = micGranted,
                cameraNeedsSettings = cameraNeedsSettings,
                micNeedsSettings = micNeedsSettings,
                httpsOn = httpsOn,
                intrinsics = intrinsics,
                cameraName = cameraName,
            )
        }
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
            SessionEvent.MuteToggled -> if (ui.micKeyEnabled || ui.workerMuted) setWorkerMuted(!ui.workerMuted)
            SessionEvent.EndClicked -> if (ui.stripShown) update { copy(confirmingEnd = true) }.also { logState("confirm") }
            SessionEvent.EndCancelled -> update { copy(confirmingEnd = false) }.also { logState("confirm-no") }
            SessionEvent.EndConfirmed -> endSession("worker")
            SessionEvent.GrantClicked ->
                if (ui.cameraNeedsSettings) CameraPermission.openSettings(context) else CameraPermission.request(context, Manifest.permission.CAMERA)
            SessionEvent.MicGrantClicked ->
                if (ui.micNeedsSettings) CameraPermission.openSettings(context) else CameraPermission.request(context, Manifest.permission.RECORD_AUDIO)
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
            is SessionEvent.DebugPermission ->
                onPermission(CameraPermission.Result(camera = event.granted, mic = ui.micGranted, cameraNever = false, micNever = ui.micNeedsSettings), force = true)
            is SessionEvent.DebugFade -> setFade(event.on)
            is SessionEvent.DebugPen -> setPen(event.on)
            is SessionEvent.DebugShare -> setShare(event.on)
            is SessionEvent.DebugVst -> {
                stopCamera("debug-vst")
                camera = VstReadbackCameraSource(context, event.width, event.height)
                startCamera("debug-vst")
            }
            is SessionEvent.DebugVoice -> debugVoice(event.what, event.arg)
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
                main.removeCallbacks(firstFrameTimeout)
                // pause stops the readback itself, not only the sending (design §1.2); the call goes on
                if (!ui.demoPicture) stopCamera("paused")
                update { copy(phase = Phase.PAUSED, penOn = false, confirmingEnd = false) }
                sendState()
                logState("paused")
            }
            Phase.PAUSED -> if (on) {
                update { copy(phase = Phase.LIVE, confirmingEnd = false) }
                streaming = true
                lastFrameMs = SystemClock.elapsedRealtime()
                videoForLive("resumed")
                sendState()
                logState("resumed")
            }
            else -> Unit
        }
    }

    private fun setData(data: DataState, error: VideoError = ui.videoError) {
        if (ui.data == data && (data != DataState.ERROR || ui.videoError == error)) return
        // no picture, no pen (sample 4.5: the pen key is off in S8)
        if (data == DataState.ERROR && ui.penOn) setPen(false)
        update { copy(data = data, videoError = if (data == DataState.ERROR) error else videoError) }
        sendState()
        logState("data-${data.wire}${if (data == DataState.ERROR) "-" + error.wire else ""}")
    }

    private fun setFade(on: Boolean) {
        if (ui.fadeOn == on) return
        update { copy(fadeOn = on) }
        server.send(Protocol.fade(on))
        scheduleFadeTick()
        logState(if (on) "fade-on" else "fade-off")
    }

    private fun setWorkerMuted(on: Boolean) {
        if (ui.workerMuted == on) return
        voice.muted = on
        update { copy(workerMuted = on) }
        sendState()
        logState(if (on) "mute-on" else "mute-off")
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

    /** T10: back to the lobby with a new PIN; the expert page is told and sees "会话已结束"; the call hangs up. */
    private fun endSession(by: String) {
        endLiveStroke()
        streaming = false
        main.removeCallbacks(reconnectTimeout)
        main.removeCallbacks(firstFrameTimeout)
        server.send(Protocol.end(by))
        server.dropExpert("ended by $by")
        stopVoice(chime = by != "debug")
        if (!ui.demoPicture) stopCamera("ended")
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
                workerMuted = false,
                expertMuted = false,
                audioLinked = false,
                expertMic = ExpertMic.UNKNOWN,
            )
        }
        voice.muted = false
        stickyData = null
        stickyError = null
        logState("ended by=$by")
    }

    private fun onPermission(r: CameraPermission.Result, force: Boolean = false) {
        val camOk = r.camera || (ui.demoPicture && !force)
        val changed = camOk != ui.permissionGranted || r.mic != ui.micGranted || r.cameraNever != ui.cameraNeedsSettings || r.micNever != ui.micNeedsSettings
        if (!changed) return
        val micBecameOk = r.mic && !ui.micGranted
        update { copy(permissionGranted = camOk, micGranted = r.mic, cameraNeedsSettings = r.cameraNever, micNeedsSettings = r.micNever) }
        logState("perm cam=$camOk mic=${r.mic}")
        if (camOk) probeVstSize()
        // a microphone granted mid-call: restart the engine so that it captures
        if (micBecameOk && voiceOn) {
            stopVoice(chime = false)
            startVoice()
        }
        // the camera granted while S8(perm) is up: try again at once
        if (camOk && ui.phase == Phase.LIVE && ui.data == DataState.ERROR && ui.videoError == VideoError.PERMISSION && stickyError == null) videoForLive("perm-granted")
        sendState()
    }

    // ---- reconnect (T9)

    override fun onExpertGone(session: String) {
        if (this.session != session && !fakeExpert) return
        if (ui.phase == Phase.LOBBY || ui.phase == Phase.RECONNECTING) return
        endLiveStroke()
        streaming = false
        main.removeCallbacks(firstFrameTimeout)
        // the call goes down with the socket and comes back with it (v3 C13)
        stopVoice(chime = false)
        if (!ui.demoPicture) stopCamera("reconnecting")
        reconnectSinceMs = SystemClock.elapsedRealtime()
        update { copy(phase = Phase.RECONNECTING, phaseBefore = phase, penOn = false, pointer = null, confirmingEnd = false, audioLinked = false) }
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
        update { copy(phase = back, expertConnected = true, audioLinked = fakeExpert || server.audioConnected, audioGrace = !(fakeExpert || server.audioConnected)) }
        main.removeCallbacks(audioGraceEnd)
        main.postDelayed(audioGraceEnd, AUDIO_GRACE_MS)
        streaming = back == Phase.LIVE
        lastFrameMs = SystemClock.elapsedRealtime()
        startVoice()
        if (back == Phase.LIVE) videoForLive("reconnected")
        sendState()
        logState("reconnected to=${back.name.lowercase()}")
    }

    // =========================================================================================
    // server
    // =========================================================================================

    override fun onHello(pin: String, session: String?, link: String): AssistServer.HelloResult {
        this.link = link
        // a known session coming back within the reconnect window
        if (session != null && session == this.session && ui.phase == Phase.RECONNECTING) {
            update { copy(link = link) }
            resumeAfterReconnect()
            return AssistServer.HelloResult.Welcome(session, welcomeJson(session), stateJson())
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
        update { copy(link = link) }
        goLive()
        // the full state travels right behind the welcome on the new socket (a send() here would race the socket's registration)
        return AssistServer.HelloResult.Welcome(id, welcomeJson(id), stateJson())
    }

    private fun welcomeJson(id: String): JSONObject =
        Protocol.welcome(id, ui.intrinsics, camera?.targetFps ?: 15, link, ui.demoPicture, ui.httpsOn)

    /** T1: lobby -> live; the call starts by itself (T19, no ringing, no answering). */
    private fun goLive() {
        main.removeCallbacks(reconnectTimeout)
        update {
            copy(
                phase = Phase.LIVE,
                phaseBefore = Phase.LIVE,
                expertConnected = true,
                penOn = false,
                confirmingEnd = false,
                data = stickyData ?: DataState.NORMAL,
                workerMuted = false,
                expertMuted = false,
                audioLinked = fakeExpert,
                audioGrace = !fakeExpert,
                expertMic = ExpertMic.UNKNOWN,
            )
        }
        main.removeCallbacks(audioGraceEnd)
        main.postDelayed(audioGraceEnd, AUDIO_GRACE_MS)
        voice.muted = false
        streaming = true
        lastFrameMs = SystemClock.elapsedRealtime()
        startVoice()
        videoForLive("live")
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
            "ping" -> server.send(Protocol.pong(message.optLong("ts", 0L), SystemClock.elapsedRealtime()))
            "mute" -> {
                val on = message.optBoolean("on", false)
                if (ui.expertMuted != on) {
                    update { copy(expertMuted = on) }
                    sendState()
                    logState(if (on) "expert-mute-on" else "expert-mute-off")
                }
            }
            "mic" -> {
                val mic = ExpertMic.of(message.optString("state"))
                if (ui.expertMic != mic) {
                    update { copy(expertMic = mic) }
                    logState("expert-mic-${mic.wire}")
                }
            }
            "video.retry" -> retryVideo()
        }
    }

    private fun stateJson(): JSONObject {
        val s = ui
        return Protocol.state(s.phase, s.shareOn, s.penOn, s.data, s.videoError, s.fadeOn, s.strokes, Protocol.VoiceState(s.workerMuted, s.expertMuted, s.micGranted))
    }

    private fun sendState() {
        server.send(stateJson())
    }

    override fun onAudioLink(open: Boolean) {
        if (fakeExpert) return
        val inCall = ui.phase == Phase.LIVE || ui.phase == Phase.PAUSED
        // connect tone once per (re)connection of the voice (v3 C13: 0.3 s, both ends)
        if (open && inCall && !ui.audioLinked) voice.chime(rising = true)
        val linked = open && ui.expertConnected
        if (ui.audioLinked == linked) return
        update { copy(audioLinked = linked, audioGrace = if (open) false else audioGrace) }
        logState(if (open) "audio-open" else "audio-closed")
    }

    private val audioGraceEnd = Runnable { if (ui.audioGrace) update { copy(audioGrace = false) }.also { logState("audio-grace-end") } }

    override fun onAudioPacket(packet: ByteArray) {
        voice.onRemotePacket(packet)
    }

    // =========================================================================================
    // camera (picture)
    // =========================================================================================

    /** Live needs a picture: on a headset (re)start the readback and show S10 until the first frame. */
    private fun videoForLive(why: String) {
        if (ui.demoPicture) {
            if (!cameraRunning) startCamera(why)
            return
        }
        stickyError?.let {
            setData(DataState.ERROR, it)
            return
        }
        main.removeCallbacks(firstFrameTimeout)
        if (!CameraPermission.granted(context)) {
            stopCamera("no-permission")
            setData(DataState.ERROR, VideoError.PERMISSION)
            return
        }
        probe?.stop()
        probe = null
        stopCamera("restart-$why")
        camera = CameraSources.create(context)
        setData(DataState.STARTING)
        startCamera(why)
        main.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS)
    }

    private val firstFrameTimeout =
        Runnable {
            if (ui.phase == Phase.LIVE && ui.data == DataState.STARTING) {
                JieyanLog.w("CAM no first frame in $FIRST_FRAME_TIMEOUT_MS ms")
                stopCamera("first-frame-timeout")
                setData(DataState.ERROR, VideoError.TIMEOUT)
            }
        }

    /** T16: [重试] on the page — tear down in the cleanup order and build again (S10). */
    private fun retryVideo() {
        if (ui.phase != Phase.LIVE) return
        JieyanLog.i("CAM retry requested")
        if (ui.demoPicture) {
            // emulator: S10 for a moment; a debug-forced S8 comes back (a retry that fails, for the countdown shot)
            val forced = stickyError
            stickyData = DataState.STARTING
            setData(DataState.STARTING)
            main.postDelayed({
                stickyData = if (forced != null) DataState.ERROR else null
                if (forced != null) setData(DataState.ERROR, forced) else setData(DataState.NORMAL)
            }, DEMO_RETRY_MS)
            return
        }
        stickyData = null
        stickyError = null
        videoForLive("retry")
    }

    private fun startCamera(why: String) {
        val cam = camera ?: return
        if (cameraRunning) return
        cameraRunning = true
        frameCount.set(0)
        lowFpsSeconds = 0
        veryLowFpsSeconds = 0
        cam.start(this)
        JieyanLog.i("CAM start why=$why source=${cam.name}")
    }

    private fun stopCamera(why: String) {
        if (!cameraRunning) return
        cameraRunning = false
        camera?.stop()
        JieyanLog.i("CAM stop why=$why")
    }

    override fun onFrame(jpeg: ByteArray, tsMs: Long) {
        lastFrameMs = tsMs
        frameCount.incrementAndGet()
        if (ui.data == DataState.STARTING && stickyData == null) main.post { onFirstFrame() }
        if (!streaming) return
        server.sendFrame(tsMs, jpeg)
    }

    /** T15: S10 -> S3 (only while the picture was starting). */
    private fun onFirstFrame() {
        if (ui.data != DataState.STARTING || stickyData != null) return
        main.removeCallbacks(firstFrameTimeout)
        setData(DataState.NORMAL)
        JieyanLog.i("CAM first frame after start")
    }

    override fun onGraphBuilt() {
        main.post {
            if (ui.phase == Phase.LIVE && ui.data == DataState.STARTING && stickyData == null) {
                main.removeCallbacks(firstFrameTimeout)
                main.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS)
            }
        }
    }

    override fun onIntrinsics(intrinsics: CameraIntrinsics) {
        main.post {
            if (intrinsics == ui.intrinsics) return@post
            update { copy(intrinsics = intrinsics) }
            VstSize.store(context, intrinsics, final = VstSize.probed(context))
            server.send(Protocol.video(intrinsics, 0f, (camera as? VstReadbackCameraSource)?.quality ?: 70))
            JieyanLog.i(String.format(Locale.US, "CAM intrinsics %dx%d fx=%.1f fy=%.1f glass=%.3fx%.3f", intrinsics.width, intrinsics.height, intrinsics.fx, intrinsics.fy, intrinsics.glassWidthM, intrinsics.glassHeightM))
        }
    }

    override fun onError(reason: String) {
        main.post {
            JieyanLog.w("CAM error: $reason")
            stopCamera("error-$reason")
            main.removeCallbacks(firstFrameTimeout)
            if (ui.phase == Phase.LIVE) setData(DataState.ERROR, VideoError.of(reason))
        }
    }

    /** Once a second: fps for the page, and the degrade steps of design §1.3. */
    private val fpsTick =
        object : Runnable {
            override fun run() {
                if (!started) return
                val n = frameCount.getAndSet(0)
                val cam = camera
                if (cameraRunning && ui.phase == Phase.LIVE && ui.data != DataState.STARTING && ui.data != DataState.ERROR) {
                    val q = (cam as? VstReadbackCameraSource)?.quality ?: SyntheticCameraSource.QUALITY
                    server.send(Protocol.video(ui.intrinsics, n.toFloat(), q))
                    if (cam is VstReadbackCameraSource && n > 0) {
                        lowFpsSeconds = if (n < DEGRADE_FPS) lowFpsSeconds + 1 else 0
                        if (lowFpsSeconds >= DEGRADE_AFTER_S) cam.degrade()
                        veryLowFpsSeconds = if (n < DEGRADE_HARD_FPS && cam.quality == VstReadbackCameraSource.QUALITY_LOW) veryLowFpsSeconds + 1 else 0
                        if (veryLowFpsSeconds == DEGRADE_AFTER_S) {
                            VstSize.setDegraded(context, true)
                            JieyanLog.w("CAM vst below $DEGRADE_HARD_FPS fps: next session uses 640x480")
                        }
                    }
                }
                main.postDelayed(this, FPS_TICK_MS)
            }
        }

    /** The VST size probe of design §1.3, once per headset, in the lobby with the camera granted. */
    private var probe: VstReadbackCameraSource? = null

    private fun probeVstSize(index: Int = 0) {
        if (ui.demoPicture || VstSize.probed(context) || probe != null) return
        if (!CameraPermission.granted(context) || ui.phase != Phase.LOBBY) return
        val (w, h) = VstSize.CANDIDATES.getOrNull(index) ?: return
        val p = VstReadbackCameraSource(context, w, h)
        probe = p
        JieyanLog.i("CAM probe ${w}x$h")
        p.start(
            object : CameraSource.Listener {
                override fun onFrame(jpeg: ByteArray, tsMs: Long) = Unit

                override fun onIntrinsics(intrinsics: CameraIntrinsics) {
                    main.post {
                        if (probe !== p) return@post
                        p.stop()
                        probe = null
                        val last = index == VstSize.CANDIDATES.lastIndex
                        val ok = VstSize.square(intrinsics)
                        JieyanLog.i("CAM probe ${w}x$h square=$ok")
                        if (ok || last) {
                            VstSize.store(context, intrinsics, final = true)
                            if (ui.phase == Phase.LOBBY) {
                                camera = CameraSources.create(context)
                                update { copy(intrinsics = intrinsics) }
                            }
                        } else {
                            probeVstSize(index + 1)
                        }
                    }
                }

                override fun onError(reason: String) {
                    main.post {
                        if (probe !== p) return@post
                        p.stop()
                        probe = null
                        JieyanLog.w("CAM probe ${w}x$h failed: $reason")
                    }
                }
            },
        )
    }

    private val staleCheck =
        object : Runnable {
            override fun run() {
                if (!started) return
                if (ui.phase == Phase.LIVE && stickyData == null && ui.data != DataState.STARTING && ui.data != DataState.ERROR) {
                    val stale = SystemClock.elapsedRealtime() - lastFrameMs > STALE_AFTER_MS
                    setData(if (stale) DataState.STALE else DataState.NORMAL)
                }
                main.postDelayed(this, STALE_CHECK_MS)
            }
        }

    private val addressCheck =
        object : Runnable {
            override fun run() {
                if (!started) return
                val address = NetworkAddress.lanIp()?.let { "$it:${AssistServer.PORT}" }
                if (address != ui.address) {
                    update { copy(address = address) }
                    JieyanLog.i("SERVER address=$address")
                }
                main.postDelayed(this, ADDRESS_CHECK_MS)
            }
        }

    /** Coming back from the system settings (S2s) is noticed here: no Activity result reaches a Stage. */
    private val permissionCheck =
        object : Runnable {
            override fun run() {
                if (!started) return
                if (!debugPermissions) onPermission(CameraPermission.current(context))
                main.postDelayed(this, PERMISSION_CHECK_MS)
            }
        }

    /** Debug states S2 / S2m / S2s pin the permission rows (the periodic check would undo them). */
    private var debugPermissions = false

    // =========================================================================================
    // voice
    // =========================================================================================

    private fun startVoice() {
        if (voiceOn) return
        voiceOn = true
        runCatching { voice.start() }
            .onSuccess { rep -> JieyanLog.i("VOICE on aec=${rep.aec} ns=${rep.ns} device=${rep.device} mic=${rep.mic}") }
            .onFailure {
                JieyanLog.e("VOICE start failed: $it", it)
                runCatching { voice.stop() }
                voiceOn = false
            }
    }

    private fun stopVoice(chime: Boolean) {
        if (!voiceOn) return
        voiceOn = false
        if (chime) {
            voice.chime(rising = false)
            // let the 0.3 s tone play out before the track goes
            main.postDelayed({ if (!voiceOn) voice.stop() }, CHIME_MS)
        } else {
            voice.stop()
        }
        _levels.value = Levels()
    }

    override fun onPacket(packet: ByteArray) {
        server.sendAudio(packet)
    }

    /** Capture thread (or the main thread for the debug ticker). */
    override fun onLevels(self: Float, remote: Float) {
        val now = SystemClock.elapsedRealtime()
        var s = self
        var r = if (_state.value.expertMuted || voice.remoteMuted) 0f else remote
        if (now < fakeSpeakUntilMs) {
            val wobble = 0.55f + 0.35f * kotlin.math.abs(kotlin.math.sin(now / 90.0)).toFloat()
            if (fakeSpeaker == "worker") s = wobble else r = wobble
        }
        synchronized(this) {
            // speaking = above the threshold for 100 ms, held 300 ms after (design S14)
            if (s >= SPEAK_LEVEL) {
                if (selfAboveSince == 0L) selfAboveSince = now
                if (now - selfAboveSince >= SPEAK_ATTACK_MS) selfSpeakUntil = now + SPEAK_HOLD_MS
            } else {
                selfAboveSince = 0L
            }
            if (r >= SPEAK_LEVEL) {
                if (remoteAboveSince == 0L) remoteAboveSince = now
                if (now - remoteAboveSince >= SPEAK_ATTACK_MS) remoteSpeakUntil = now + SPEAK_HOLD_MS
            } else {
                remoteAboveSince = 0L
            }
            _levels.value = Levels(s, r, now < selfSpeakUntil, now < remoteSpeakUntil)
        }
    }

    /** Without a running engine (emulator, simulated expert) `speak` still drives the levels from here. */
    private val levelTicker =
        object : Runnable {
            override fun run() {
                if (!voiceOn || voice.report?.mic != true) onLevels(0f, 0f)
                if (SystemClock.elapsedRealtime() < fakeSpeakUntilMs + SPEAK_HOLD_MS + 100) main.postDelayed(this, 50L)
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

    /**
     * v3 hooks: `mute worker|expert on|off`, `speak worker|expert [ms]`, `expertmic ok|insecure|denied`,
     * `drop audio`, `audio on|off`, `https on|off`, `never cam|mic|both|off`, `mic denied|granted`,
     * `fps N` (emulator picture rate), `vstreset` (forget the probed VST size).
     */
    private fun debugVoice(what: String, arg: String) {
        when (what) {
            "mute" -> {
                val words = arg.split(' ', ':')
                val who = words.getOrNull(0) ?: "worker"
                val on = words.getOrNull(1) != "off"
                if (who == "expert") {
                    update { copy(expertMuted = on) }
                    sendState()
                } else {
                    setWorkerMuted(on)
                }
            }
            "speak" -> {
                fakeSpeaker = if (arg.startsWith("worker")) "worker" else "expert"
                fakeSpeakUntilMs = SystemClock.elapsedRealtime() + (arg.substringAfter(' ', "").toLongOrNull() ?: 4_000L)
                main.removeCallbacks(levelTicker)
                main.post(levelTicker)
            }
            "expertmic" -> update { copy(expertMic = ExpertMic.of(arg)) }
            "drop" -> {
                server.dropVoice("debug")
                update { copy(audioLinked = false) }
            }
            "audio" -> update { copy(audioLinked = arg != "off") }
            "https" -> {
                val on = arg != "off"
                if (on) tls.start() else tls.stop()
                server.httpsAvailable = on && tls.running
                update { copy(httpsOn = on && tls.running) }
            }
            "never" -> {
                debugPermissions = arg != "off"
                when (arg) {
                    "cam" -> update { copy(permissionGranted = false, cameraNeedsSettings = true) }
                    "mic" -> update { copy(micGranted = false, micNeedsSettings = true) }
                    "both" -> update { copy(permissionGranted = false, cameraNeedsSettings = true, micGranted = false, micNeedsSettings = true) }
                    else -> onPermission(CameraPermission.current(context))
                }
            }
            "mic" -> {
                debugPermissions = arg == "denied" || arg == "off"
                update { copy(micGranted = !debugPermissions, micNeedsSettings = false) }
            }
            "fps" -> (camera as? SyntheticCameraSource)?.fps = arg.toIntOrNull() ?: SyntheticCameraSource.FPS
            "vstreset" -> VstSize.reset(context)
            "tone" -> voice.toneUntilMs = SystemClock.elapsedRealtime() + (arg.toLongOrNull() ?: 3_000L)
        }
        logState("debug-$what $arg")
    }

    /** Puts the app in a named design state (`state S8:timeout`, `state S14:worker` …) with a simulated expert where one is needed. */
    private fun debugState(name: String) {
        val parts = name.split(':', ' ')
        val id = parts[0].uppercase(Locale.US)
        val arg = parts.getOrNull(1)?.lowercase(Locale.US)
        when (id) {
            "S1" -> {
                endSession("debug")
                debugPermissions = false
                debugVoice("https", "on")
                onPermission(CameraPermission.current(context))
            }
            "S2" -> {
                endSession("debug")
                debugPermissions = true
                update { copy(permissionGranted = false, cameraNeedsSettings = false, micGranted = arg != "both", micNeedsSettings = false) }
            }
            "S2M" -> {
                endSession("debug")
                debugPermissions = true
                update { copy(micGranted = false, micNeedsSettings = false, permissionGranted = arg != "both", cameraNeedsSettings = false) }
            }
            "S2S" -> {
                endSession("debug")
                debugVoice("never", arg ?: "both")
            }
            "S1U" -> {
                endSession("debug")
                debugVoice("https", "off")
            }
            "S3", "S3D" -> {
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
            "S6A" -> {
                liveBase()
                setPen(false)
                debugVoice("drop", "audio")
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
                val reason = VideoError.of(arg ?: "readback")
                stickyError = reason
                if (!ui.demoPicture) stopCamera("debug-S8")
                setData(DataState.ERROR, reason)
            }
            "S9" -> {
                liveBase()
                setPen(false)
                update { copy(confirmingEnd = true) }
            }
            "S10" -> {
                liveBase()
                setPen(false)
                stickyData = DataState.STARTING
                setData(DataState.STARTING)
            }
            "S11" -> {
                liveBase()
                setPen(false)
                debugVoice("fps", arg ?: "5")
            }
            "S12" -> {
                liveBase()
                setPen(false)
                setWorkerMuted(true)
            }
            "S13" -> {
                liveBase()
                setPen(false)
                debugVoice("mute", "expert on")
            }
            "S14" -> {
                liveBase()
                setPen(false)
                debugVoice("speak", "${arg ?: "expert"} 8000")
            }
            "S15" -> {
                liveBase()
                setPen(false)
                debugVoice("expertmic", arg ?: "insecure")
            }
            else -> JieyanLog.w("CMD state unknown '$name'")
        }
        logState("debug-$name")
    }

    /**
     * Every debug state S3+ starts from the same base: live, picture normal, both unmuted, voice
     * linked, no confirmation. Without it a jump from S8 to S4 stayed in S8 (no picture, no pen).
     */
    private fun liveBase() {
        debugPermissions = false
        ensureLive()
        stickyData = null
        stickyError = null
        (camera as? SyntheticCameraSource)?.fps = SyntheticCameraSource.FPS
        fakeSpeakUntilMs = 0L
        setWorkerMuted(false)
        update { copy(confirmingEnd = false, expertMuted = false, expertMic = ExpertMic.UNKNOWN, audioLinked = fakeExpert || server.audioConnected) }
        if ((ui.data == DataState.ERROR || ui.data == DataState.STARTING) && !ui.demoPicture) videoForLive("debug-base") else setData(DataState.NORMAL)
        sendState()
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
        o.put("mic", s.micGranted)
        o.put("cameraNeedsSettings", s.cameraNeedsSettings)
        o.put("micNeedsSettings", s.micNeedsSettings)
        o.put("https", s.httpsOn)
        o.put("pinLocked", s.pinLocked)
        o.put("expert", s.expertConnected)
        o.put("expertSocket", server.connected)
        o.put("audioSocket", server.audioConnected)
        o.put("audioLinked", s.audioLinked)
        o.put("link", s.link)
        o.put("fakeExpert", fakeExpert)
        o.put("session", session)
        o.put("share", s.shareOn)
        o.put("pen", s.penOn)
        o.put("fade", s.fadeOn)
        o.put("data", s.data.wire)
        o.put("videoError", s.videoError.wire)
        o.put("workerMuted", s.workerMuted)
        o.put("expertMuted", s.expertMuted)
        o.put("expertMic", s.expertMic.wire)
        o.put("voiceOn", voiceOn)
        o.put("voiceStats", voice.stats.joinToString(","))
        voice.report?.let { o.put("aec", it.aec).put("ns", it.ns).put("audioDevice", it.device) }
        o.put("levels", _levels.value.let { String.format(Locale.US, "%.2f/%.2f %s/%s", it.self, it.remote, it.selfSpeaking, it.remoteSpeaking) })
        o.put("confirmingEnd", s.confirmingEnd)
        o.put("strokes", s.strokes.size)
        o.put("strokesExpert", s.strokes.count { it.owner == Owner.EXPERT })
        o.put("strokesWorker", s.strokes.count { it.owner == Owner.WORKER })
        o.put("liveStroke", s.liveStroke?.points?.size ?: 0)
        o.put("pointer", s.pointer?.let { JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()) })
        o.put("camera", s.cameraName)
        o.put("cameraRunning", cameraRunning)
        (camera as? VstReadbackCameraSource)?.let { o.put("vstQuality", it.quality).put("vstStats", it.stats.toString()) }
        o.put("image", "${s.intrinsics.width}x${s.intrinsics.height}")
        o.put("fx", s.intrinsics.fx.toDouble()).put("fy", s.intrinsics.fy.toDouble())
        o.put("glassM", String.format(Locale.US, "%.3fx%.3f", s.intrinsics.glassWidthM, s.intrinsics.glassHeightM))
        o.put("lastFrameAgoMs", if (lastFrameMs == 0L) -1 else SystemClock.elapsedRealtime() - lastFrameMs)
        o.put("streaming", streaming)
        o.put("serverRunning", server.running)
        o.put("tlsRunning", tls.running)
        return o.toString()
    }

    // =========================================================================================

    private inline fun update(block: SessionUiState.() -> SessionUiState) {
        _state.value = _state.value.block()
    }

    private fun logState(why: String) {
        val s = ui
        JieyanLog.i("STATE ${s.designState} phase=${s.phase.name.lowercase()} why=$why pin=${s.pin} expert=${s.expertConnected} pen=${s.penOn} data=${s.data.wire} strokes=${s.strokes.size} mute=${s.workerMuted}/${s.expertMuted} audio=${s.audioLinked}")
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
        private const val PERMISSION_CHECK_MS = 1_500L
        private const val FPS_TICK_MS = 1_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val PROBE_DELAY_MS = 3_000L
        private const val AUDIO_GRACE_MS = 3_000L
        private const val DEMO_RETRY_MS = 1_200L
        private const val RECONNECT_TIMEOUT_MS = 30_000L
        private const val PIN_TRIES = 5
        private const val PIN_LOCK_MS = 60_000L
        private const val UNCLEAR_WINDOW_MS = 8_000L
        private const val LIVE_FLUSH_MS = 33L
        private const val FADE_TICK_MS = 100L
        private const val CHIME_MS = 350L
        private const val DEGRADE_FPS = 12
        private const val DEGRADE_HARD_FPS = 10
        private const val DEGRADE_AFTER_S = 3
        private const val SPEAK_LEVEL = 0.35f
        private const val SPEAK_ATTACK_MS = 100L
        private const val SPEAK_HOLD_MS = 300L
    }
}
