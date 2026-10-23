package io.embodit.jieyan.ui.session.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.pico.spatial.ui.foundation.content.SpatialView
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.ui.session.SessionEvent
import io.embodit.jieyan.ui.session.SessionUiState
import io.embodit.jieyan.ui.session.debug.LocalProbePanel
import io.embodit.jieyan.ui.session.debug.Probe
import io.embodit.jieyan.ui.session.scene.JieyanScene
import io.embodit.jieyan.ui.session.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView that carries the panel pool. Every attachment panel is
 * declared here once, with a fixed size, whether it is showing or not; [JieyanScene] parents each panel
 * entity under its holder and shows or hides the holder.
 *
 * Window model (design-doc): the lobby card, then the call strip; the visor glass is content, not a
 * control, except while the worker's pen is on.
 */
@Composable
internal fun JieyanStage(state: SessionUiState, levels: kotlinx.coroutines.flow.StateFlow<io.embodit.jieyan.ui.session.SessionViewModel.Levels>, scene: JieyanScene, onEvent: (SessionEvent) -> Unit) {
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        attachments = {
            AttachmentPanel(PanelIds.LOBBY) {
                PoolPanel(PanelIds.LOBBY, scene) { inView ->
                    LobbyCard(state = if (inView) state else null, onEvent = onEvent)
                }
            }
            AttachmentPanel(PanelIds.STRIP) {
                PoolPanel(PanelIds.STRIP, scene, pressed = { scene.onStripPointer() }) { inView ->
                    CallStrip(state = if (inView) state else null, levels = levels, onEvent = onEvent)
                }
            }
            AttachmentPanel(PanelIds.GLASS) {
                PoolPanel(PanelIds.GLASS, scene) { inView ->
                    VisorGlass(state = if (inView) state else null, onEvent = onEvent)
                }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled panel
 * draws its content only then (what it draws while parked out of sight stays broken on the emulator).
 * [pressed] hears every press on the panel (the strip holds still while its keys are used).
 */
@Composable
private fun PoolPanel(id: String, scene: JieyanScene, pressed: (() -> Unit)? = null, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        if (logged || pressed != null) {
            Modifier.pointerInput(id, logged) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) continue
                        if (event.type == PointerEventType.Press) pressed?.invoke()
                        if (logged) {
                            val p = event.changes.firstOrNull()?.position
                            JieyanLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                        }
                    }
                }
            }
        } else Modifier
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
