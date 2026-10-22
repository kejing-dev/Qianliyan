package io.embodit.jieyan.ui.session.scene

import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.jieyan.domain.FacePanel
import io.embodit.jieyan.domain.HeadPose
import io.embodit.jieyan.domain.PanelFacing
import io.embodit.jieyan.domain.Quat4
import io.embodit.jieyan.domain.Vec3
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.ui.session.debug.Probe
import java.util.Locale
import kotlin.math.abs

/**
 * The pre-built panel pool (ported from Spatial Ruler, SDK 6.1.9 findings on the emulator). Every
 * attachment panel is declared once, with a fixed size, and gets a holder entity here. A panel appears
 * by enabling its holder and moving it into place and disappears by disabling the holder and taking it
 * out of the room: nothing is created at that moment.
 *
 * 1. A panel entity parented under a disabled holder never appears: a slot stays enabled, parked out
 *    of view at 1/1000 size, until its panel is bound and has had [SETTLE_FRAMES] / [SETTLE_MS] to come up.
 * 2. A parked panel below a head anchor takes the pointer away from every panel on the line of sight
 *    through that anchor: hidden = holder disabled *and* stowed (room panels 30 m behind the viewer,
 *    head-mounted ones 40 m below).
 * 3. What a panel draws while it is out of place stays broken: a panel composes its content only after
 *    it has been in view for [SHOW_FRAMES] engine frames ([onVisibility]).
 * 4. No `LookAtComponent`: the turn towards the viewer is written into the holder every frame
 *    ([faceViewer]), so picture and pointer agree.
 */
internal class PanelRig(private val stageRoot: Entity, private val onVisibility: (id: String, shown: Boolean) -> Unit) {

    enum class Mount { WORLD, HEAD }

    enum class Rest { SHOWN, PARKED, STOWED }

    private var frame = 0L
    private var viewer: Vec3? = null
    private var stow: Vec3 = Vec3(0f, -40f, 0f)
    private var stowSet = false

    inner class Slot(val id: String, val holder: Entity, val mount: Mount, val widthDp: Float, val heightDp: Float) {
        var panel: Entity? = null
            private set
        var shown = false
            private set

        /** Where the panel is when shown: Stage metres for WORLD, the head frame for HEAD. */
        var position: Vec3 = Vec3.ZERO
            private set

        /** Scale along the panel's width and height (a head-locked glass is sized by the camera intrinsics). */
        var scaleX = 1f
            private set
        var scaleY = 1f
            private set

        var headOffset: Vec3? = null
        var headRotation: Quat4 = Quat4.IDENTITY

        /** The entity that owns the slot: destroyed with it, and disabled while the slot is hidden. */
        var switch: Entity = holder

        var facing: PanelFacing = PanelFacing(0f, 0f)
            private set
        private var faced = false

        var settled = false
            private set
        var rest = Rest.PARKED
            private set
        private var boundFrame = 0L
        private var boundMs = 0L
        private var writtenPosition: Vec3? = null
        private var writtenScale: Vec3? = null
        private var switchOn = true
        private var reported: Boolean? = null
        private var shownFrame = 0L

        val holderEnabled: Boolean
            get() = switchOn

        val lastYaw: Float?
            get() = if (faced) facing.yaw else null

        internal fun bindPanel(entity: Entity, nowMs: Long) {
            settled = false
            apply()
            holder.addChild(entity)
            panel = entity
            runCatching { entity.setName("panel:$id") }
            boundFrame = frame
            boundMs = nowMs
        }

        internal fun forgetPanel() {
            panel = null
            settled = false
        }

        internal fun settle(nowMs: Long) {
            if (reported != shown) report()
            if (settled || panel == null) return
            if (frame - boundFrame < SETTLE_FRAMES || nowMs - boundMs < SETTLE_MS) return
            settled = true
            apply()
        }

        private fun report() {
            val visible = shown && frame - shownFrame >= SHOW_FRAMES
            if (reported != visible) {
                reported = visible
                onVisibility(id, visible)
            }
        }

        internal fun face(viewer: Vec3) {
            val f = FacePanel.facing(position, viewer, lastYaw)
            if (faced && angle(f.yaw, facing.yaw) < FACE_STEP_RAD && abs(f.pitch - facing.pitch) < FACE_STEP_RAD) return
            facing = f
            faced = true
            holder.setLocal(rotation = f.rotation)
        }

        /** Shows the panel; [at] moves a WORLD slot, null keeps the last value. */
        fun show(at: Vec3? = null, kx: Float? = null, ky: Float? = null) {
            if (at != null) position = at
            if (kx != null) scaleX = kx
            if (ky != null) scaleY = ky
            shown = true
            if (mount == Mount.WORLD) viewer?.let { face(it) }
            apply()
        }

        /** Moves a shown WORLD slot (lazy follow); no-op while hidden. */
        fun moveTo(at: Vec3) {
            position = at
            if (!shown) return
            viewer?.let { face(it) }
            apply()
        }

        fun hide() {
            if (!shown) return
            shown = false
            apply()
        }

        internal fun home(at: Vec3, kx: Float, ky: Float, visible: Boolean) {
            position = at
            scaleX = kx
            scaleY = ky
            shown = visible
            apply()
        }

        internal fun apply() {
            val wanted =
                when {
                    shown -> Rest.SHOWN
                    !settled -> Rest.PARKED
                    else -> Rest.STOWED
                }
            val away = wanted != Rest.SHOWN
            val p = if (away) awayPosition() else position
            val k = if (away) Vec3(PARK_SCALE, PARK_SCALE, PARK_SCALE) else Vec3(scaleX, scaleY, 1f)
            val wantSwitch = wanted != Rest.STOWED
            if (!wantSwitch && switchOn) {
                switch.enabled = false
                switchOn = false
            }
            if (p != writtenPosition) {
                holder.setLocal(position = p)
                writtenPosition = p
            }
            if (k != writtenScale) {
                holder.setLocal(scale = k)
                writtenScale = k
            }
            if (wantSwitch && !switchOn) {
                switch.enabled = true
                switchOn = true
            }
            if (wanted != rest) {
                if (wanted == Rest.SHOWN) shownFrame = frame
                rest = wanted
                if (Probe.enabled && panel != null) JieyanLog.i("RIG slot id=$id ${wanted.name.lowercase(Locale.US)}")
            }
            report()
        }

        private fun awayPosition(): Vec3 = if (mount == Mount.WORLD) stow else BELOW_HEAD

        fun enginePosition(): Vec3? = runCatching { holder.convertPositionTo(Vector3.ZERO, stageRoot).domain() }.getOrNull()?.takeIf { it.isFinite() }

        fun engineRotation(): Quat4? = runCatching { holder.convertRotationTo(Quat.identity(), stageRoot).domain() }.getOrNull()
    }

    private val slots = LinkedHashMap<String, Slot>()

    val all: Collection<Slot>
        get() = slots.values

    operator fun get(id: String): Slot? = slots[id]

    /** Once per rendered frame: lets freshly bound panels settle (finding 1), then stows the hidden ones. */
    fun onFrame(nowMs: Long) {
        frame++
        for (slot in slots.values) slot.settle(nowMs)
    }

    /** Keeps the stowing place of hidden room panels 30 m behind the viewer. */
    fun stowBehind(head: HeadPose) {
        val to = stow - head.position
        val range = to.length
        val ahead = if (range < 1e-3f) 1f else to.dot(head.forward) / range
        if (stowSet && range in STOW_MIN_M..STOW_MAX_M && ahead < STOW_AHEAD_COS) return
        stowSet = true
        val f = head.forward
        val back = Vec3(-f.x, 0f, -f.z).let { if (it.length < 0.1f) Vec3(0f, 0f, 1f) else it.normalized() }
        stow = head.position + back * STOW_DISTANCE_M
        for (slot in slots.values) if (slot.mount == Mount.WORLD && !slot.shown) slot.apply()
    }

    /** Turns every shown room panel towards [viewer] (Stage metres): yaw, then pitch, never roll. */
    fun faceViewer(viewer: Vec3) {
        this.viewer = viewer
        for (slot in slots.values) if (slot.mount == Mount.WORLD && slot.shown) slot.face(viewer)
    }

    /** A panel that stands in the room; [faceViewer] keeps it turned towards the viewer. */
    fun world(id: String, widthDp: Float, heightDp: Float, scale: Float): Slot {
        val holder = Entity()
        runCatching { holder.setName("holder:$id") }
        stageRoot.addChild(holder)
        return Slot(id, holder, Mount.WORLD, widthDp, heightDp).also {
            it.home(Vec3.ZERO, scale, scale, visible = false)
            slots[id] = it
        }
    }

    /**
     * A panel locked to the head (`AnchorTarget.createCameraTarget()`): [offset] is in the head frame
     * (x right, y up, -z ahead). The panel stays parallel to the image plane (the visor glass).
     */
    fun head(id: String, offset: Vec3, widthDp: Float, heightDp: Float): Slot {
        val anchor = Entity()
        runCatching { anchor.setName("holder:$id") }
        anchor.components.set(AnchorComponent(AnchorTarget.createCameraTarget()).apply { positionOffset = offset.sdk() })
        stageRoot.addChild(anchor)
        // the anchor component owns the anchor entity's transform: rotation, position and scale live on a child
        val scaler = Entity()
        anchor.addChild(scaler)
        return Slot(id, scaler, Mount.HEAD, widthDp, heightDp).also {
            it.headOffset = offset
            it.switch = anchor
            it.home(Vec3.ZERO, 1f, 1f, visible = false)
            slots[id] = it
        }
    }

    /** Parents freshly composed panel entities under their holders. Returns true when none is missing. */
    fun bind(attachments: SpatialViewAttachments, ids: Collection<String>, nowMs: Long): Boolean {
        var complete = true
        for (id in ids) {
            val slot = slots[id] ?: continue
            if (slot.panel != null) continue
            val panel = attachments.entity(id)
            if (panel == null) {
                complete = false
                continue
            }
            slot.bindPanel(panel, nowMs)
            JieyanLog.i("PANEL bound id=$id")
        }
        return complete
    }

    /** The framework owns the panel entities: let go of them before the holders are destroyed. */
    fun release() {
        for (slot in slots.values) {
            runCatching { slot.holder.removeAllChildren() }
            slot.forgetPanel()
        }
        slots.clear()
    }

    /** One line per slot for the debug command `panels`. */
    fun dump(): List<String> =
        slots.values.map { slot ->
            String.format(
                Locale.US,
                "PANEL id=%s mount=%s rest=%s shown=%s bound=%s settled=%s enabled=%s pos=%s scale=%.4fx%.4f size_dp=%.0fx%.0f engine=%s %s",
                slot.id,
                slot.mount.name.lowercase(Locale.US),
                slot.rest.name.lowercase(Locale.US),
                slot.shown,
                slot.panel != null,
                slot.settled,
                slot.holderEnabled,
                JieyanLog.pos(slot.position),
                slot.scaleX,
                slot.scaleY,
                slot.widthDp,
                slot.heightDp,
                JieyanLog.pos(slot.enginePosition()),
                JieyanLog.quat(slot.engineRotation()),
            )
        }

    companion object {
        private const val PARK_SCALE = 0.001f
        private val BELOW_HEAD = Vec3(0f, -40f, 0f)
        private const val STOW_DISTANCE_M = 30f
        private const val STOW_MIN_M = 20f
        private const val STOW_MAX_M = 45f
        private const val STOW_AHEAD_COS = 0.26f
        private const val SHOW_FRAMES = 2L
        private const val SETTLE_FRAMES = 6L
        private const val SETTLE_MS = 600L
        private const val FACE_STEP_RAD = 0.004f

        private fun angle(a: Float, b: Float): Float {
            var d = a - b
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            return abs(d)
        }
    }
}
