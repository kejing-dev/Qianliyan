package io.embodit.jieyan.ui.session.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.DotBadge
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.LinearProgressDefaults
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleIconButton
import com.pico.spatial.ui.design.ToggleIconButtonDefaults
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.jieyan.R
import io.embodit.jieyan.domain.DataState
import io.embodit.jieyan.domain.Phase
import io.embodit.jieyan.domain.VideoError
import io.embodit.jieyan.ui.session.SessionEvent
import io.embodit.jieyan.ui.session.SessionUiState
import io.embodit.jieyan.ui.session.SessionViewModel
import kotlinx.coroutines.flow.StateFlow
import io.embodit.jieyan.ui.session.debug.probe
import io.embodit.jieyan.ui.theme.JieyanColors
import io.embodit.jieyan.ui.theme.JieyanType

/** The call strip is 680 x 112 dp (v3 C12), shown at 0.42 x 0.07 m, 0.9 m ahead and 22 degrees below the eye. */
internal val CallStripWidth = 680.dp
internal val CallStripHeight = 112.dp
private val KeySize = 56.dp

/**
 * The only panel in view while sharing (design-doc §3 "通话条", v3 §4): the live dot, a fixed slot for
 * the expert's three-bar voice wave, one line of status (the highest of the v3 priority list) and six
 * 56 dp icon keys (mic, pen, undo, clear, share, end); the mic key carries a 2 dp level ring that
 * follows the worker's own voice. The end confirmation is inline: the strip
 * replaces status and keys by one sentence and 是 / 否 (no floating dialog in a Stage). While
 * reconnecting a 2 dp progress line runs along the bottom edge and the pen keys are off.
 *
 * [state] is null while the panel is parked out of view: nothing is drawn then.
 */
@Composable
internal fun CallStrip(state: SessionUiState?, levels: StateFlow<SessionViewModel.Levels>, onEvent: (SessionEvent) -> Unit) {
    Box(modifier = Modifier.size(CallStripWidth, CallStripHeight)) {
        if (state == null) return@Box
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier =
                Modifier
                    .size(CallStripWidth, CallStripHeight)
                    .clip(shape)
                    .backgroundMaterial(enable = true, style = Material.Regular)
                    .then(glassRefresh())
                    .probe("strip"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.confirmingEnd) ConfirmRow(onEvent) else StatusAndKeys(state, levels, onEvent)
            }
            if (state.phase == Phase.RECONNECTING) ReconnectLine()
        }
    }
}

@Composable
private fun RowScopeStatus(state: SessionUiState, levels: StateFlow<SessionViewModel.Levels>, modifier: Modifier) {
    val scheme = PicoTheme.colorScheme
    val error = state.phase == Phase.LIVE && state.data == DataState.ERROR
    val liveOn = state.phase == Phase.LIVE && !error
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                error -> StatusGlyph(R.drawable.ic_warning, scheme.error)
                state.phase == Phase.RECONNECTING -> StatusGlyph(R.drawable.ic_retry, scheme.alert)
                state.phase == Phase.PAUSED -> StatusGlyph(R.drawable.ic_pause, scheme.alert)
                liveOn -> BreathingDot(JieyanColors.LiveRed)
            }
        }
        // the expert's voice: a fixed slot, so the text never moves (v3 S14: 声波不改文字)
        VoiceWave(levels = levels, active = state.audioLinked && !state.expertMuted && state.phase != Phase.RECONNECTING)
        // one line, the highest of the v3 priority list (v3 §2.3)
        val (text, color) =
            when {
                error -> "${stringResource(R.string.strip_error)} · ${stringResource(errorPhrase(state.videoError))}" to scheme.error
                state.phase == Phase.RECONNECTING -> stringResource(R.string.strip_reconnecting) to scheme.alert
                state.phase == Phase.PAUSED -> stringResource(R.string.strip_paused) to scheme.alert
                state.voiceDropped -> stringResource(R.string.strip_voice_reconnecting) to scheme.alert
                state.data == DataState.STARTING -> stringResource(R.string.strip_starting) to scheme.labelSecondary
                state.data == DataState.STALE -> stringResource(R.string.strip_stale) to scheme.alert
                state.penOn -> stringResource(R.string.strip_pen_on) to scheme.labelPrimary
                state.workerMuted -> stringResource(R.string.strip_self_muted) to scheme.labelPrimary
                !state.expertMic.canTalk -> stringResource(R.string.strip_expert_listen_only) to scheme.labelSecondary
                state.expertMuted -> stringResource(R.string.strip_expert_muted) to scheme.labelSecondary
                else -> stringResource(R.string.strip_live) to scheme.labelPrimary
            }
        Text(text = text, color = color, style = JieyanType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("status"))
    }
}

private fun errorPhrase(e: VideoError): Int =
    when (e) {
        VideoError.PERMISSION -> R.string.strip_error_perm
        VideoError.TIMEOUT -> R.string.strip_error_timeout
        VideoError.READBACK -> R.string.strip_error_readback
        VideoError.NO_VST -> R.string.strip_error_novst
    }

/** Three bars in passable green while the expert speaks; an empty slot otherwise (custom Canvas, not a control). */
@Composable
private fun VoiceWave(levels: StateFlow<SessionViewModel.Levels>, active: Boolean) {
    val lv by levels.collectAsState()
    val on = active && lv.remoteSpeaking
    val green = PicoTheme.colorScheme.passable
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Restart), label = "wavePhase")
    Canvas(modifier = Modifier.size(18.dp, 18.dp).probe("wave")) {
        if (!on) return@Canvas
        val bw = size.width / 5f
        for (i in 0..2) {
            val t = (phase + i * 0.33f) % 1f
            val tri = if (t < 0.5f) t * 2 else (1 - t) * 2
            val h = size.height * (0.35f + 0.65f * tri) * (0.6f + 0.4f * lv.remote)
            drawRoundRect(green, topLeft = Offset(i * 2 * bw, (size.height - h) / 2), size = Size(bw, h), cornerRadius = CornerRadius(bw / 2))
        }
    }
}

/** 🎙 (leftmost, v3 C12): `ToggleIconButton`, checked = muted (alert container, 🎙̸); a 2 dp ring around it follows the worker's level. */
@Composable
private fun MicKey(state: SessionUiState, levels: StateFlow<SessionViewModel.Levels>, onEvent: (SessionEvent) -> Unit) {
    val scheme = PicoTheme.colorScheme
    val lv by levels.collectAsState()
    val enabled = state.micKeyEnabled || state.workerMuted
    val ringAlpha = if (enabled && !state.workerMuted) (0.15f + 0.85f * lv.self).coerceIn(0f, 1f) * (if (lv.selfSpeaking) 1f else 0.6f) else 0f
    Box(
        modifier = Modifier.size(KeySize + 8.dp).border(2.dp, scheme.passable.copy(alpha = ringAlpha), CircleShape).probe("mic_ring"),
        contentAlignment = Alignment.Center,
    ) {
        ToggleIconButton(
            checked = state.workerMuted,
            onCheckedChange = { onEvent(SessionEvent.MuteToggled) },
            modifier = Modifier.probe("mic"),
            colors =
                ToggleIconButtonDefaults.toggleIconButtonColors(
                    checkedContainerColor = scheme.alert,
                    checkedContentColor = JieyanColors.InkOnChalk,
                    uncheckedContainerColor = scheme.fillPrimary,
                    uncheckedContentColor = scheme.labelPrimaryLight,
                ),
            size = ToggleIconButtonDefaults.toggleIconButtonSize(width = KeySize, height = KeySize),
            enabled = enabled,
        ) {
            Icon(
                painter = painterResource(if (state.workerMuted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                contentDescription = stringResource(if (state.workerMuted) R.string.key_mic_off else R.string.key_mic_on),
                modifier = Modifier.size(24.dp),
                tint = if (state.workerMuted) JieyanColors.InkOnChalk else scheme.labelPrimaryLight,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatusAndKeys(state: SessionUiState, levels: StateFlow<SessionViewModel.Levels>, onEvent: (SessionEvent) -> Unit) {
    val scheme = PicoTheme.colorScheme
    val reconnecting = state.phase == Phase.RECONNECTING
    RowScopeStatus(state, levels, Modifier.weight(1f))
    MicKey(state, levels, onEvent)
    val penEnabled = state.drawKeysEnabled
    ToggleIconButton(
        checked = state.penOn,
        onCheckedChange = { onEvent(SessionEvent.PenToggled) },
        modifier = Modifier.probe("pen"),
        colors =
            ToggleIconButtonDefaults.toggleIconButtonColors(
                checkedContainerColor = scheme.interaction,
                checkedContentColor = JieyanColors.InkOnChalk,
                uncheckedContainerColor = scheme.fillPrimary,
                uncheckedContentColor = scheme.labelPrimaryLight,
            ),
        size = ToggleIconButtonDefaults.toggleIconButtonSize(width = KeySize, height = KeySize),
        enabled = penEnabled,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_pen),
            contentDescription = stringResource(R.string.key_pen),
            modifier = Modifier.size(24.dp),
            tint = if (state.penOn) JieyanColors.InkOnChalk else scheme.labelPrimaryLight,
        )
    }
    Key(name = "undo", icon = R.drawable.ic_undo, description = stringResource(R.string.key_undo), enabled = state.canUndo) { onEvent(SessionEvent.UndoClicked) }
    Key(name = "clear", icon = R.drawable.ic_trash, description = stringResource(R.string.key_clear), enabled = state.canClear) { onEvent(SessionEvent.ClearClicked) }
    ToggleIconButton(
        checked = state.shareOn,
        onCheckedChange = { onEvent(SessionEvent.ShareToggled) },
        modifier = Modifier.probe("share"),
        colors =
            ToggleIconButtonDefaults.toggleIconButtonColors(
                checkedContainerColor = scheme.fillPrimary,
                checkedContentColor = scheme.labelPrimaryLight,
                uncheckedContainerColor = scheme.fillPrimary,
                uncheckedContentColor = scheme.alert,
            ),
        size = ToggleIconButtonDefaults.toggleIconButtonSize(width = KeySize, height = KeySize),
        enabled = !reconnecting,
    ) {
        Icon(
            painter = painterResource(if (state.shareOn) R.drawable.ic_eye else R.drawable.ic_eye_off),
            contentDescription = stringResource(if (state.shareOn) R.string.key_share_on else R.string.key_share_off),
            modifier = Modifier.size(24.dp),
            tint = if (state.shareOn) scheme.labelPrimaryLight else scheme.alert,
        )
    }
    Key(name = "end", icon = R.drawable.ic_power, description = stringResource(R.string.key_end), enabled = true) { onEvent(SessionEvent.EndClicked) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ConfirmRow(onEvent: (SessionEvent) -> Unit) {
    val scheme = PicoTheme.colorScheme
    Text(
        text = stringResource(R.string.strip_confirm_end),
        color = scheme.labelPrimary,
        style = JieyanType.body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).probe("confirm_text"),
    )
    ConfirmButton(name = "confirm_yes", text = stringResource(R.string.strip_yes), container = scheme.interaction, content = JieyanColors.InkOnChalk) { onEvent(SessionEvent.EndConfirmed) }
    ConfirmButton(name = "confirm_no", text = stringResource(R.string.strip_no), container = scheme.fillPrimary, content = scheme.labelPrimaryLight) { onEvent(SessionEvent.EndCancelled) }
}

private val ConfirmButtonSize = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 96.dp)

@Composable
private fun ConfirmButton(name: String, text: String, container: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ConfirmButtonSize,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text = text, color = content, style = JieyanType.body)
    }
}

@Composable
private fun Key(name: String, icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = scheme.fillPrimary, contentColor = scheme.labelPrimaryLight),
        size = IconButtonDefaults.iconButtonSize(width = KeySize, height = KeySize),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(24.dp), tint = scheme.labelPrimaryLight)
    }
}

/** The live dot: `sui-dot-badge` in liveRed, breathing (design: 实心圆点 + 呼吸). */
@Composable
private fun BreathingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "breath",
        )
    Box(modifier = Modifier.size(14.dp).alpha(alpha).probe("live_dot"), contentAlignment = Alignment.Center) {
        DotBadge(modifier = Modifier, color = color)
    }
}

@Composable
private fun StatusGlyph(icon: Int, tint: Color) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
}

/** 2 dp line along the bottom edge while reconnecting (`sui-linear-progress`, alert colour, sweeping). */
@Composable
private fun ReconnectLine() {
    val transition = rememberInfiniteTransition(label = "reconnect")
    val progress by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "sweep",
        )
    val scheme = PicoTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth().height(6.dp).padding(horizontal = 24.dp, vertical = 2.dp).probe("progress")) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            colors = LinearProgressDefaults.linearProgressColors(scheme.alert, scheme.fillTertiary), // ProgressColors(indicatorColor, backgroundColor)
        )
    }
    Spacer(modifier = Modifier.width(0.dp))
}
