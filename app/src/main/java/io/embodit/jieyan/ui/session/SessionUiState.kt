package io.embodit.jieyan.ui.session

import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.domain.DataState
import io.embodit.jieyan.domain.Owner
import io.embodit.jieyan.domain.Phase
import io.embodit.jieyan.domain.Pointer
import io.embodit.jieyan.domain.Stroke
import io.embodit.jieyan.domain.StrokePoint

/**
 * Everything the panels show. One immutable value per change; the per-frame work of the scene (head
 * pose, lazy follow, glass transform) never goes through it.
 */
data class SessionUiState(
    val phase: Phase = Phase.LOBBY,
    /** Phase to return to after a reconnect (LIVE or PAUSED). */
    val phaseBefore: Phase = Phase.LIVE,
    val pin: String = "0000",
    /** `http://<ip>:8765`, or null while no LAN address is known. */
    val address: String? = null,
    val permissionGranted: Boolean = true,
    /** PIN gate locked after 5 wrong tries (60 s). */
    val pinLocked: Boolean = false,
    val expertConnected: Boolean = false,
    val penOn: Boolean = false,
    val fadeOn: Boolean = false,
    val data: DataState = DataState.NORMAL,
    val strokes: List<Stroke> = emptyList(),
    /** The worker's stroke under the finger (drawn live, not yet in [strokes]). */
    val liveStroke: Stroke? = null,
    val pointer: Pointer? = null,
    /** S9: the inline end confirmation on the call strip. */
    val confirmingEnd: Boolean = false,
    /** Ticks while a stroke is fading, so that the glass redraws (100 ms). */
    val fadeTick: Long = 0L,
    val intrinsics: CameraIntrinsics = CameraIntrinsics(640, 360, 609.5f, 609.5f, 320f, 180f),
    val cameraName: String = "synthetic",
) {
    val shareOn: Boolean
        get() = phase == Phase.LIVE

    /** Lobby card visible (S1 / S2). */
    val lobbyShown: Boolean
        get() = phase == Phase.LOBBY

    /** Call strip visible (S3 - S9). */
    val stripShown: Boolean
        get() = phase != Phase.LOBBY

    /** Visor glass in place (strokes shown): every phase but the lobby. */
    val glassShown: Boolean
        get() = phase != Phase.LOBBY

    /** Pen, undo and clear work only while live with a picture (sample 4.3 / 4.4 / 4.5: off in S5, S6, S8). */
    val drawKeysEnabled: Boolean
        get() = phase == Phase.LIVE && data != DataState.ERROR

    val canUndo: Boolean
        get() = drawKeysEnabled && strokes.any { it.owner == Owner.WORKER }

    val canClear: Boolean
        get() = drawKeysEnabled && strokes.isNotEmpty()

    /** The named design state the headset is in (QA screenshots are listed by these). */
    val designState: String
        get() =
            when {
                phase == Phase.LOBBY && !permissionGranted -> "S2"
                phase == Phase.LOBBY -> "S1"
                confirmingEnd -> "S9"
                phase == Phase.RECONNECTING -> "S6"
                phase == Phase.PAUSED -> "S5"
                data == DataState.ERROR -> "S8"
                data == DataState.STALE -> "S7"
                penOn -> "S4"
                else -> "S3"
            }
}

/** What the panels, the expert page and the debug receiver tell the ViewModel. */
sealed class SessionEvent {
    // call strip
    data object PenToggled : SessionEvent()

    data object UndoClicked : SessionEvent()

    data object ClearClicked : SessionEvent()

    data object ShareToggled : SessionEvent()

    data object EndClicked : SessionEvent()

    data object EndConfirmed : SessionEvent()

    data object EndCancelled : SessionEvent()

    // lobby card
    data object GrantClicked : SessionEvent()

    // visor glass (worker's pen), normalised coordinates
    data class GlassStrokeBegin(val at: StrokePoint) : SessionEvent()

    data class GlassStrokePoint(val at: StrokePoint) : SessionEvent()

    data object GlassStrokeEnd : SessionEvent()

    // debug hooks (debug builds only; they put the app in a designed state the emulator cannot reach)
    data class DebugState(val name: String) : SessionEvent()

    data class DebugExpert(val connected: Boolean) : SessionEvent()

    data class DebugStroke(val owner: Owner, val points: List<StrokePoint>, val color: String?, val width: Float?) : SessionEvent()

    data class DebugPointer(val at: StrokePoint?) : SessionEvent()

    data class DebugData(val data: DataState) : SessionEvent()

    data class DebugReconnect(val on: Boolean, val timeout: Boolean = false) : SessionEvent()

    data class DebugPermission(val granted: Boolean) : SessionEvent()

    data class DebugFade(val on: Boolean) : SessionEvent()

    data class DebugPen(val on: Boolean) : SessionEvent()

    data class DebugShare(val on: Boolean) : SessionEvent()
}
