package io.embodit.jieyan.ui.session.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.ui.session.debug.LocalProbePanel
import io.embodit.jieyan.ui.session.debug.Probe
import kotlinx.coroutines.delay

/**
 * Keeps a glass panel's backdrop fresh (QA round 2, q2-1).
 *
 * `backgroundMaterial` is a property of the panel's graphics layer: the backdrop is composited into the
 * panel's frame when that layer is drawn. On the emulator a frame drawn before the backdrop is ready comes
 * up black over the region that frame repainted (the lobby card after ⏻ → 是: black from the new PIN down),
 * and a static panel never draws again, so the black stayed until the card was hidden. The call strip
 * recovered from the same black frame within a second or two because its live dot redraws all the time.
 *
 * Put this right after `backgroundMaterial(...)`: it re-records the whole material layer every
 * [REFRESH_MS] while the panel is composed, so a bad frame is repainted on the next tick.
 */
@Composable
internal fun glassRefresh(): Modifier {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            tick.intValue++
        }
    }
    val panel = LocalProbePanel.current
    val draws = remember { IntArray(2) } // draws so far, tick of the last log line
    // reading the tick in the draw phase invalidates the layer's drawing, not the composition
    return Modifier.drawBehind {
        val n = tick.intValue
        draws[0]++
        if (Probe.enabled && (draws[0] == 1 || n - draws[1] >= LOG_EVERY)) {
            draws[1] = n
            JieyanLog.i("GLASS redraw panel=$panel tick=$n draws=${draws[0]}")
        }
    }
}

private const val LOG_EVERY = 20

private const val REFRESH_MS = 500L
