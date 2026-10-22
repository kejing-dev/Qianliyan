package io.embodit.jieyan.platform.camera

import android.content.Context
import android.os.Build
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.platform.JieyanLog

/**
 * Where the shared picture comes from. One JPEG per frame, at most [targetFps]; frames are produced on
 * a worker thread and the newest one wins (never a queue: remote assist wants "now", not a recording).
 *
 * Two sources:
 * - [SyntheticCameraSource]: a drawn scene with a clock and a moving marker, used on the emulator (no
 *   VST there) and as the fallback on a device until the readback path is validated.
 * - [VstReadbackCameraSource]: SpatialML Readback Mode (`rectifiedVSTAccess` -> `convertColor` -> global
 *   dynamic texture -> `readbackContentSuspend`). Needs the CAMERA permission and a headset; not
 *   implemented in v0.1.0 (QA / device station), see its file for the contract.
 */
interface CameraSource {
    interface Listener {
        /** A new JPEG of the camera image; [tsMs] is `SystemClock.elapsedRealtime()` of the capture. Worker thread. */
        fun onFrame(jpeg: ByteArray, tsMs: Long)

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
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    /**
     * Picks the source for this run. [preferVst]: ask for the headset's camera when it is available;
     * the VST source reports whether it can run on this build. Everything else gets the synthetic source.
     */
    fun create(context: Context, preferVst: Boolean): CameraSource {
        if (preferVst && !onEmulator && VstReadbackCameraSource.available(context)) {
            JieyanLog.i("CAM source=vst")
            return VstReadbackCameraSource(context)
        }
        JieyanLog.i("CAM source=synthetic emulator=$onEmulator preferVst=$preferVst")
        return SyntheticCameraSource(context)
    }
}
