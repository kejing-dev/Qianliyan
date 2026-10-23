package io.embodit.jieyan.platform.camera

import android.content.Context
import android.os.Build
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.platform.CameraPermission
import io.embodit.jieyan.platform.JieyanLog

/**
 * Where the shared picture comes from. One JPEG per frame, at most [targetFps]; frames are produced on
 * a worker thread and the newest one wins (never a queue: remote assist wants "now", not a recording).
 *
 * Two sources:
 * - [SyntheticCameraSource]: a drawn scene with a clock and a moving marker, the emulator's demo picture
 *   (no VST there; the page marks it 「演示画面」, S3d). Never used on a headset.
 * - [VstReadbackCameraSource]: SpatialML Readback Mode (`rectifiedVSTAccess` -> `convertColor` -> global
 *   RGBA tensor -> `readbackContentSuspend`). Needs the CAMERA permission and a headset.
 */
interface CameraSource {
    interface Listener {
        /** A new JPEG of the camera image; [tsMs] is `SystemClock.elapsedRealtime()` of the capture. Worker thread. */
        fun onFrame(jpeg: ByteArray, tsMs: Long)

        /** A (new) readback graph is built; the 5 s first-frame limit counts from here (v3 §1.3: per build). Worker thread. */
        fun onGraphBuilt() {}

        /** The real intrinsics arrived (VST: after the first frame). Worker thread. */
        fun onIntrinsics(intrinsics: CameraIntrinsics) {}

        /** The source cannot deliver (readback failed, camera unavailable). Worker thread. */
        fun onError(reason: String)
    }

    /** Size of the image and the intrinsics the glass is sized by (fixed for one session). */
    val intrinsics: CameraIntrinsics

    val targetFps: Int

    val name: String

    fun start(listener: Listener)

    fun stop()
}

object CameraSources {
    /** The emulator has no VST image: SpatialML's sensor operators deliver no data there. */
    val onEmulator: Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.MODEL.contains("x86_64", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    /**
     * Picks the source for this run (design v3 C5): the synthetic demo picture only on the emulator; on a
     * headset always the VST readback, at the size the lobby probe chose ([VstSize]). Without the camera
     * permission the VST source reports `perm` on start (S8), it never falls back to a drawn picture.
     */
    fun create(context: Context): CameraSource {
        if (onEmulator) {
            JieyanLog.i("CAM source=synthetic (emulator)")
            return SyntheticCameraSource(context)
        }
        val (w, h) = VstSize.chosen(context)
        JieyanLog.i("CAM source=vst ${w}x$h permission=${CameraPermission.granted(context)}")
        return VstReadbackCameraSource(context, w, h)
    }
}

/**
 * The VST size of this headset, chosen once in the lobby (design v3 §1.3): 960 x 540 first; if its
 * intrinsics say the pixels are not square (|fx - fy| / fx > 3 %) then 1024 x 768, then 512 x 512.
 * Remembered with the intrinsics so a session never has to rebuild the graph to find out. [degraded]:
 * the last session stayed below 10 fps, the next one uses 640 x 480.
 */
object VstSize {
    val CANDIDATES = listOf(960 to 540, 1024 to 768, 512 to 512)
    private const val SQUARE_TOLERANCE = 0.03f

    private fun prefs(context: Context) = context.getSharedPreferences("vst", Context.MODE_PRIVATE)

    fun chosen(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        if (p.getBoolean("degraded", false)) return 640 to 480
        val w = p.getInt("w", 0)
        val h = p.getInt("h", 0)
        return if (w > 0 && h > 0) w to h else CANDIDATES.first()
    }

    fun probed(context: Context): Boolean = prefs(context).getInt("w", 0) > 0

    fun cachedIntrinsics(context: Context): CameraIntrinsics? {
        val p = prefs(context)
        val (w, h) = chosen(context)
        val fx = p.getFloat("fx", 0f)
        val fy = p.getFloat("fy", 0f)
        if (fx <= 0f || fy <= 0f || p.getInt("kw", 0) != w) return null
        return CameraIntrinsics(w, h, fx, fy, p.getFloat("cx", w / 2f), p.getFloat("cy", h / 2f))
    }

    fun square(k: CameraIntrinsics): Boolean = kotlin.math.abs(k.fx - k.fy) / k.fx <= SQUARE_TOLERANCE

    fun store(context: Context, k: CameraIntrinsics, final: Boolean) {
        prefs(context).edit().apply {
            putInt("kw", k.width)
            putFloat("fx", k.fx)
            putFloat("fy", k.fy)
            putFloat("cx", k.cx)
            putFloat("cy", k.cy)
            if (final) {
                putInt("w", k.width)
                putInt("h", k.height)
            }
        }.apply()
    }

    fun setDegraded(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("degraded", on).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
