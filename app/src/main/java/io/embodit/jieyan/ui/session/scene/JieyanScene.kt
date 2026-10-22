package io.embodit.jieyan.ui.session.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.domain.HeadPose
import io.embodit.jieyan.domain.Quat4
import io.embodit.jieyan.domain.Vec3
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.platform.tracking.HeadTracker
import io.embodit.jieyan.ui.session.SessionUiState
import io.embodit.jieyan.ui.session.components.CallStripHeight
import io.embodit.jieyan.ui.session.components.CallStripWidth
import io.embodit.jieyan.ui.session.components.LobbyCardHeight
import io.embodit.jieyan.ui.session.components.LobbyCardWidth
import io.embodit.jieyan.ui.session.components.VisorGlassHeight
import io.embodit.jieyan.ui.session.components.VisorGlassWidth
import java.util.Locale
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object ActiveScene {
    @Volatile var scene: JieyanScene? = null
}

/** The one per-frame ECS system of the app. */
class JieyanFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Panel ids of the pool. */
object PanelIds {
    const val LOBBY = "lobbyCard"
    const val STRIP = "callStrip"
    const val GLASS = "visorGlass"
    val pool = listOf(LOBBY, STRIP, GLASS)
}

/**
 * The 3D side of the app: one calibrated stage root, the panel pool, the head-locked visor glass and the
 * lazy-follow placement of the lobby card and the call strip. Per-frame work happens in [onFrame] and
 * never goes through Compose; Compose state is applied through [render]. Main thread only.
 *
 * Layout (design-doc §3 "Layout"): lobby card 1.2 m straight ahead; call strip 0.9 m ahead, 22 degrees
 * below the line of sight, turned to face the eye; visor glass head-locked at 1.0 m, parallel to the
 * image plane, W/fx x H/fy metres (the same line of sight as the camera picture).
 */
class JieyanScene {

    /** Panel id -> the panel is in view. A pooled panel composes its content only while this is true. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val headTracker = HeadTracker()
    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var rig: PanelRig? = null
    private var attached = false
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastPoseLogMs = 0L

    private val lobbyFollow = LazyFollow("lobby", aheadM = LOBBY_DISTANCE_M, downM = 0.05f, windowDeg = 30f, nearM = 0.8f, farM = 1.7f)
    private val stripFollow = LazyFollow("strip", aheadM = STRIP_DISTANCE_M * cos(STRIP_DOWN_RAD), downM = STRIP_DISTANCE_M * sin(STRIP_DOWN_RAD), windowDeg = 42f, nearM = 0.6f, farM = 1.25f)

    private var ui = SessionUiState()
    private var veil: GlassVeil? = null
    private var lastKeyPressMs = NEVER

    /** A key of the strip is pressed: the strip holds still for a moment (lazy follow). */
    fun onStripPointer() {
        lastKeyPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        this.content = content
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z for a full-size
        // view, Spatial Ruler). Move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        JieyanLog.i("STAGE root offset=${JieyanLog.pos(offset.domain())} check=${JieyanLog.pos(root.convertPositionTo(Vector3.ZERO, null).domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.LOBBY, LobbyCardWidth.value, LobbyCardHeight.value, LOBBY_SCALE)
        rig.world(PanelIds.STRIP, CallStripWidth.value, CallStripHeight.value, STRIP_SCALE)
        val glassSlot = rig.head(PanelIds.GLASS, Vec3(0f, 0f, -GLASS_DISTANCE_M), VisorGlassWidth.value, VisorGlassHeight.value)
        // the veil lives in the holder's (unscaled) panel frame: the slot's kx / ky stretch it like the panel
        veil = GlassVeil(glassSlot.holder, VisorGlassWidth.value * DP_M, VisorGlassHeight.value * DP_M)
        headTracker.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        render(ui)
        JieyanLog.i("SCENE attached")
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    /** Stops everything [attach] started and destroys the entities. */
    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        headTracker.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        runCatching { stageRoot?.destroy(true) }
        stageRoot = null
        rig = null
        veil = null
        content = null
        hasHead = false
        lobbyFollow.reset()
        stripFollow.reset()
        JieyanLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities
    // =========================================================================================

    /** Idempotent: which panels stand where, and how big the glass is. */
    fun render(state: SessionUiState) {
        ui = state
        val rig = rig ?: return
        val lobby = rig[PanelIds.LOBBY]
        val strip = rig[PanelIds.STRIP]
        val glass = rig[PanelIds.GLASS]
        if (state.lobbyShown) lobby?.show() else lobby?.hide()
        if (state.stripShown) strip?.show() else strip?.hide()
        if (state.glassShown) {
            val (kx, ky) = glassScale(state.intrinsics)
            glass?.show(kx = kx, ky = ky)
        } else {
            glass?.hide()
        }
        veil?.show(state.glassShown && state.penOn)
        // the inline confirmation brings the strip 8 dp nearer (design: 条体前移 +8 dp)
        stripFollow.lift = if (state.confirmingEnd) CONFIRM_LIFT_M else 0f
        if (!state.lobbyShown) lobbyFollow.reset()
        if (!state.stripShown) stripFollow.reset()
    }

    /** Glass at 1 m: W/fx x H/fy metres, as scale of the 1280 x 720 dp panel (1 dp = 0.8 mm). */
    private fun glassScale(i: CameraIntrinsics): Pair<Float, Float> {
        val kx = (i.glassWidthM * GLASS_DISTANCE_M) / (VisorGlassWidth.value * DP_M)
        val ky = (i.glassHeightM * GLASS_DISTANCE_M) / (VisorGlassHeight.value * DP_M)
        return kx.coerceIn(0.3f, 3f) to ky.coerceIn(0.3f, 3f)
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head)
        if (!attached) return
        // the tracker's first poses are the origin: wait for a real head pose before placing anything
        if (hasHead && head.position.length > 1e-3f) {
            val hold = lastKeyPressMs != NEVER && now - lastKeyPressMs < KEY_HOLD_MS || ui.confirmingEnd
            rig[PanelIds.LOBBY]?.let { slot -> if (slot.shown) lobbyFollow.step(head, now, hold = false)?.let { slot.moveTo(it) } }
            rig[PanelIds.STRIP]?.let { slot -> if (slot.shown) stripFollow.step(head, now, hold)?.let { slot.moveTo(it) } }
        }
        rig.faceViewer(head.position)
        if (now - lastPoseLogMs > POSE_LOG_MS) {
            lastPoseLogMs = now
            JieyanLog.i("FRAME n=$frames head=${JieyanLog.pos(head.position)} fwd=${JieyanLog.pos(head.forward)} lobby=${JieyanLog.pos(rig[PanelIds.LOBBY]?.position)} strip=${JieyanLog.pos(rig[PanelIds.STRIP]?.position)}")
        }
    }

    // =========================================================================================
    // debug
    // =========================================================================================

    fun poseLine(): String =
        String.format(
            Locale.US,
            "POSE hmd pos=%s %s fwd=%s up=%s valid=%s support=%s frames=%d",
            JieyanLog.pos(head.position),
            JieyanLog.quat(head.rotation),
            JieyanLog.pos(head.forward),
            JieyanLog.pos(head.up),
            hasHead,
            headTracker.supportState(),
            frames,
        )

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    /** Test hook: both lazy-follow panels slide to their rest pose now. */
    fun park() {
        lobbyFollow.reset()
        stripFollow.reset()
    }

    companion object {
        const val LOBBY_DISTANCE_M = 1.2f
        const val STRIP_DISTANCE_M = 0.9f
        const val GLASS_DISTANCE_M = 1.0f
        private const val STRIP_DOWN_RAD = 0.384f // 22 degrees

        /** Lobby card 640 dp wide at 1.2 m = 0.56 m (design); call strip 616 dp = 0.38 m. */
        const val LOBBY_SCALE = 0.56f / (640f * DP_M)
        const val STRIP_SCALE = 0.38f / (616f * DP_M)
        private const val CONFIRM_LIFT_M = 8f * DP_M
        private const val KEY_HOLD_MS = 600L
        private const val POSE_LOG_MS = 5_000L
        private const val REBIND_DELAY_MS = 250L
        private const val NEVER = -1L
    }
}

/**
 * Lazy follow (Spatial Ruler T11): the panel rests where it is; once its centre has been more than
 * [windowDeg] off the view direction, or nearer than [nearM] or farther than [farM], for [OUT_MS]
 * without a break, it slides (0.35 s, ease-in-out cubic) to the rest pose of that moment: [aheadM]
 * along the level heading, [downM] below the eye. [lift] moves it towards the head (inline confirm).
 */
internal class LazyFollow(private val name: String, private val aheadM: Float, private val downM: Float, private val windowDeg: Float, private val nearM: Float, private val farM: Float) {
    private var rest: Vec3? = null
    private var restHead: Vec3 = Vec3.ZERO
    private var from: Vec3 = Vec3.ZERO
    private var to: Vec3 = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    var lift = 0f

    fun reset() {
        rest = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    private fun designed(head: HeadPose): Vec3 {
        val f = head.forward
        val yaw = if (hypot(f.x, f.z) < 0.05f) 0f else atan2(-f.x, -f.z)
        val ahead = Vec3(-sin(yaw), 0f, -cos(yaw))
        return head.position + ahead * aheadM + Vec3(0f, -downM, 0f)
    }

    /** Where the panel should be this frame, or null when nothing changed. */
    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = rest
        if (current == null) {
            val p = designed(head)
            rest = p
            restHead = head.position
            JieyanLog.i("FOLLOW $name park pos=${JieyanLog.pos(p)}")
            return write(p, head)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val p = from.lerp(to, easeInOutCubic(q))
            if (q >= 1f) {
                slideStartMs = NEVER
                rest = to
            }
            return write(p, head)
        }
        val toPanel = current - head.position
        val dist = toPanel.length
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((toPanel / dist).dot(head.forward).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val reason =
            when {
                away > windowDeg -> "angle"
                dist < nearM -> "near"
                dist > farM -> "far"
                else -> null
            }
        if (reason == null || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = designed(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            JieyanLog.i(String.format(Locale.US, "FOLLOW %s slide reason=%s angle=%.1f dist=%.2f to=%s", name, reason, away, dist, JieyanLog.pos(to)))
            return write(current, head)
        }
        return write(current, head)
    }

    private fun write(p: Vec3, head: HeadPose): Vec3? {
        val lifted = if (lift > 0f) p + (head.position - p).normalized() * lift else p
        if (lifted == lastWritten) return null
        lastWritten = lifted
        return lifted
    }

    private fun easeInOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
    }
}
