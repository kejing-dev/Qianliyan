package io.embodit.jieyan.ui.session.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.jieyan.R
import io.embodit.jieyan.domain.Stroke as ChalkStroke
import io.embodit.jieyan.domain.StrokePoint
import io.embodit.jieyan.ui.session.SessionEvent
import io.embodit.jieyan.ui.session.SessionUiState
import io.embodit.jieyan.ui.session.debug.probe
import io.embodit.jieyan.ui.theme.JieyanColors
import io.embodit.jieyan.ui.theme.JieyanType

/**
 * The visor glass renders at 1024 x 576 dp (2048 x 1152 px at the panel density of 2); its physical size
 * follows the camera intrinsics (scene). A 1280 x 720 dp panel (2560 x 1440 px) came up with its bottom
 * 9 % black on the emulator (SDK 6.1.9), so the panel stays inside 2048 px.
 */
internal val VisorGlassWidth = 1024.dp
internal val VisorGlassHeight = 576.dp

/**
 * The visor glass (design-doc §3 "视野玻璃"): no material, pure transparency. It carries both parties'
 * chalk lines (normalised 0..1 coordinates of the camera image, halo-outlined) and the expert's pointer
 * dot. While the worker's pen is on it shows a 2 dp chalk-blue frame and the chip "在玻璃上画 · 专家同步看到"
 * (the 4 % white veil is the ECS quad `GlassVeil` behind the panel), and takes the pointer: a
 * press-and-drag is a blue stroke. This panel carries no material and no full-area fill (QA q1-1:
 * see `PenChip` and `GlassVeil`).
 *
 * Low-frequency UI content: Compose Canvas, not ECS. [state] is null while the panel is parked.
 */
@Composable
internal fun VisorGlass(state: SessionUiState?, onEvent: (SessionEvent) -> Unit) {
    Box(modifier = Modifier.size(VisorGlassWidth, VisorGlassHeight)) {
        if (state == null) return@Box
        val penOn = state.penOn
        // the 4 % veil is an ECS quad behind this panel (GlassVeil): a translucent fill over the whole
        // attachment panel writes depth and blacked out the room behind it (QA q1-1)
        val frame = if (penOn) Modifier.border(2.dp, JieyanColors.ChalkBlue, RoundedCornerShape(12.dp)) else Modifier
        val nowMs = state.fadeTick.let { SystemClock.elapsedRealtime() }
        Canvas(
            modifier =
                Modifier
                    .size(VisorGlassWidth, VisorGlassHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .then(frame)
                    .probe("glass")
                    .pointerInput(penOn) {
                        if (!penOn) return@pointerInput
                        awaitPointerEventScope {
                            var down = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull() ?: continue
                                val at = StrokePoint((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                                when {
                                    !down && change.pressed -> {
                                        down = true
                                        onEvent(SessionEvent.GlassStrokeBegin(at))
                                    }
                                    down && change.pressed -> onEvent(SessionEvent.GlassStrokePoint(at))
                                    down && !change.pressed -> {
                                        down = false
                                        onEvent(SessionEvent.GlassStrokeEnd)
                                    }
                                }
                                change.consume()
                            }
                        }
                    },
        ) {
            for (stroke in state.strokes) drawStroke(stroke, stroke.alpha(nowMs, state.fadeOn))
            state.liveStroke?.let { drawStroke(it, 1f) }
            state.pointer?.let { p ->
                val c = Offset(p.x * size.width, p.y * size.height)
                drawCircle(JieyanColors.StrokeHalo, radius = 11.dp.toPx(), center = c)
                drawCircle(JieyanColors.ChalkYellow, radius = 8.dp.toPx(), center = c)
            }
        }
        if (penOn) PenChip(modifier = Modifier.align(Alignment.TopStart).padding(20.dp))
    }
}

/** Chalk line: a dark halo under the colour so that it reads on bright passthrough (design `strokeHalo`). */
private fun DrawScope.drawStroke(stroke: ChalkStroke, alpha: Float) {
    if (stroke.points.isEmpty() || alpha <= 0f) return
    val width = stroke.width.dp.toPx() * (size.width / 1280.dp.toPx()) // stroke widths are in px of the 1280-wide reference frame
    val color = JieyanColors.chalk(stroke.color)
    if (stroke.points.size == 1) {
        val c = Offset(stroke.points[0].x * size.width, stroke.points[0].y * size.height)
        drawCircle(JieyanColors.StrokeHalo.copy(alpha = JieyanColors.StrokeHalo.alpha * alpha), radius = width / 2f + 2.dp.toPx(), center = c)
        drawCircle(color.copy(alpha = alpha), radius = width / 2f, center = c)
        return
    }
    val path = Path()
    stroke.points.forEachIndexed { i, p ->
        val x = p.x * size.width
        val y = p.y * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, JieyanColors.StrokeHalo.copy(alpha = JieyanColors.StrokeHalo.alpha * alpha), style = Stroke(width = width + 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = alpha), style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * "在玻璃上画 · 专家同步看到": a small pill with the pen glyph (`sui-chip small`).
 *
 * A solid slate pill, not a `backgroundMaterial` glass: the visor glass has no material (design-doc §3),
 * and a backdrop material anywhere in this full-view panel made the emulator draw the panel's whole
 * backdrop as an opaque black band, the call strip's glass with it (QA round 1, q1-1).
 */
@Composable
private fun PenChip(modifier: Modifier) {
    val scheme = PicoTheme.colorScheme
    Row(
        modifier = modifier.clip(CircleShape).background(JieyanColors.ChipInk).padding(horizontal = 14.dp, vertical = 8.dp).probe("pen_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(R.drawable.ic_pen), contentDescription = null, modifier = Modifier.size(16.dp), tint = JieyanColors.ChalkBlue)
        Box(modifier = Modifier.size(8.dp))
        Text(text = stringResource(R.string.glass_chip_pen), color = scheme.labelPrimaryLight, style = JieyanType.label, maxLines = 1)
    }
}
