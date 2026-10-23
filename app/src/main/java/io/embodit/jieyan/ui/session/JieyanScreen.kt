package io.embodit.jieyan.ui.session

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.ui.session.components.JieyanStage
import io.embodit.jieyan.ui.session.debug.Probe
import io.embodit.jieyan.ui.session.scene.JieyanFrameSystem
import io.embodit.jieyan.ui.session.scene.JieyanScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together and owns
 * nothing else: the ViewModel holds the session (server, camera, strokes), the [JieyanScene] places the
 * panels in the room, and [JieyanStage] declares them.
 *
 * Lifecycle rule of the design: leaving the Stage stops readback, socket, server and strokes.
 */
@Composable
fun JieyanScreen() {
    val context = LocalContext.current
    val viewModel: SessionViewModel = viewModel(factory = SessionViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { JieyanScene() }

    DisposableEffect(scene) {
        registerSystem<JieyanFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun dispatch(event: SessionEvent) = viewModel.onEvent(event)

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun sceneLines(what: String): List<String> =
                    when (what) {
                        "pose" -> listOf(scene.poseLine())
                        "panels" -> scene.panelLines()
                        "park" -> {
                            scene.park()
                            listOf("FOLLOW park requested")
                        }
                        else -> emptyList()
                    }

                override fun press(control: String): Boolean {
                    val s = viewModel.state.value
                    val event =
                        when (control) {
                            "pen" -> SessionEvent.PenToggled.takeIf { s.stripShown && !s.confirmingEnd }
                            "undo" -> SessionEvent.UndoClicked.takeIf { s.stripShown && s.canUndo && !s.confirmingEnd }
                            "clear" -> SessionEvent.ClearClicked.takeIf { s.stripShown && s.canClear && !s.confirmingEnd }
                            "share" -> SessionEvent.ShareToggled.takeIf { s.stripShown && !s.confirmingEnd }
                            "end" -> SessionEvent.EndClicked.takeIf { s.stripShown && !s.confirmingEnd }
                            "confirm_yes" -> SessionEvent.EndConfirmed.takeIf { s.confirmingEnd }
                            "confirm_no" -> SessionEvent.EndCancelled.takeIf { s.confirmingEnd }
                            "grant" -> SessionEvent.GrantClicked.takeIf { s.lobbyShown && !s.permissionGranted }
                            else -> null
                        } ?: return false
                    viewModel.onEvent(event)
                    return true
                }
            }
        viewModel.start()
        JieyanLog.i("STAGE enter")
        onDispose {
            Probe.host = null
            viewModel.stop()
            unregisterSystem<JieyanFrameSystem>()
            scene.detach()
            JieyanLog.i("STAGE disposed")
        }
    }

    // model -> entities (which panels stand where)
    LaunchedEffect(scene, state.phase, state.confirmingEnd, state.permissionGranted, state.intrinsics, state.penOn) { scene.render(state) }

    JieyanStage(state = state, levels = viewModel.levels, scene = scene, onEvent = viewModel::onEvent)
}
