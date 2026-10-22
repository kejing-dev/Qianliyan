package io.embodit.jieyan.ui.session.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import io.embodit.jieyan.ui.session.SessionEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Seams for the debug receiver (QA station). Nothing here is product UI and nothing here does anything
 * by itself: the hooks are only driven by `src/debug`. In a release build no receiver exists,
 * [Probe.enabled] stays false and the bounds modifiers are no-ops.
 */
object Probe {
    /** Set once at start-up from the debuggable flag of the app. */
    @Volatile var enabled: Boolean = false

    /** Bounds of one control inside its panel, panel-local pixels, origin top-left. */
    data class ControlBounds(val cx: Float, val cy: Float, val w: Float, val h: Float)

    private val controls = ConcurrentHashMap<String, ConcurrentHashMap<String, ControlBounds>>()
    private val panelSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun report(panel: String, name: String, bounds: ControlBounds, panelWidth: Int, panelHeight: Int) {
        controls.getOrPut(panel) { ConcurrentHashMap() }[name] = bounds
        panelSizes[panel] = panelWidth to panelHeight
    }

    fun forget(panel: String, name: String) {
        controls[panel]?.remove(name)
    }

    fun controlsOf(panel: String): Map<String, ControlBounds> = controls[panel]?.toSortedMap() ?: emptyMap()

    fun sizeOf(panel: String): Pair<Int, Int>? = panelSizes[panel]

    /** What the screen exposes to the debug receiver while it is composed. */
    interface Host {
        fun dispatch(event: SessionEvent)

        fun dumpJson(): String

        /** `POSE ...`, `PANEL ...` lines from the scene. */
        fun sceneLines(what: String): List<String>

        /** Presses a control by its stable name; false when it is not on screen. */
        fun press(control: String): Boolean
    }

    @Volatile var host: Host? = null
}

/** The panel a control belongs to (set by the stage for every attachment panel). */
val LocalProbePanel = compositionLocalOf { "?" }

/** Registers a control's bounds under its stable name (debug builds only; a no-op otherwise). */
@Composable
fun Modifier.probe(name: String): Modifier {
    if (!Probe.enabled) return this
    val panel = LocalProbePanel.current
    DisposableEffect(panel, name) { onDispose { Probe.forget(panel, name) } }
    return this.onGloballyPositioned { c ->
        if (c.isAttached) {
            val root = c.findRootCoordinates()
            val topLeft = root.localPositionOf(c, Offset.Zero)
            val bottomRight = root.localPositionOf(c, Offset(c.size.width.toFloat(), c.size.height.toFloat()))
            Probe.report(
                panel,
                name,
                Probe.ControlBounds((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                root.size.width,
                root.size.height,
            )
        }
    }
}
