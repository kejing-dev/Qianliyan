package io.embodit.jieyan.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vector in Stage metres (right-handed: +X right, +Y up, -Z ahead of the user at Stage start).
 * Plain JVM type, no SDK dependency (ported from Spatial Ruler).
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Float
        get() = sqrt(dot(this))

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-12f) this else this / l
    }

    fun lerp(o: Vec3, t: Float): Vec3 = this + (o - this) * t

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val RIGHT = Vec3(1f, 0f, 0f)

        /** "Ahead" in Stage space (the SDK's own `Vector3.FORWARD` is +Z: never use that for aiming). */
        val AHEAD = Vec3(0f, 0f, -1f)
    }
}

/** Unit quaternion, Hamilton convention: `(a * b).rotate(v) == a.rotate(b.rotate(v))`. */
data class Quat4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }

    fun conjugate() = Quat4(-x, -y, -z, w)

    operator fun times(o: Quat4) =
        Quat4(
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
            w * o.w - x * o.x - y * o.y - z * o.z,
        )

    fun normalized(): Quat4 {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-12f) IDENTITY else Quat4(x / l, y / l, z / l, w / l)
    }

    companion object {
        val IDENTITY = Quat4(0f, 0f, 0f, 1f)

        fun axisAngle(unitAxis: Vec3, radians: Float): Quat4 {
            val s = sin(radians / 2f)
            return Quat4(unitAxis.x * s, unitAxis.y * s, unitAxis.z * s, cos(radians / 2f))
        }

        /** Shortest rotation that takes unit vector [a] onto unit vector [b]. */
        fun fromTo(a: Vec3, b: Vec3): Quat4 {
            val d = a.dot(b)
            if (d > 0.99999f) return IDENTITY
            if (d < -0.99999f) {
                val helper = if (abs(a.x) < 0.9f) Vec3.RIGHT else Vec3.UP
                val axis = a.cross(helper).normalized()
                return Quat4(axis.x, axis.y, axis.z, 0f)
            }
            val c = a.cross(b)
            return Quat4(c.x, c.y, c.z, 1f + d).normalized()
        }
    }
}

/** Pose of the viewer's head in Stage metres: -Z of [rotation] is the viewing direction. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)
}

/** How a room panel is turned: yaw about the vertical and pitch towards the viewer, radians; no roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
}

/**
 * Facing rule for panels that stand in the room: turn about the vertical towards the head, then pitch
 * towards the head (clamped to 60 degrees), never roll. Straight above or below, the last yaw is kept.
 */
object FacePanel {
    private const val MAX_PITCH_RAD = 1.0471976f
    private const val KEEP_YAW_WITHIN_M = 0.1f

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?): PanelFacing {
        val to = head - panel
        val flat = kotlin.math.hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else kotlin.math.atan2(to.x, to.z)
        val pitch = kotlin.math.atan2(to.y, flat).coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)
        return PanelFacing(yaw, pitch)
    }
}
