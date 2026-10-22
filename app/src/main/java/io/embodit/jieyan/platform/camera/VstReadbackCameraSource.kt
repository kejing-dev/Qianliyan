package io.embodit.jieyan.platform.camera

import android.content.Context
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.platform.CameraPermission
import io.embodit.jieyan.platform.JieyanLog

/**
 * The headset's camera through SpatialML Readback Mode (the only sanctioned way for an app to read VST
 * pixels on PICO OS 6.1; networking.md §2). **Not implemented in v0.1.0** — it needs a Swan and the
 * SpatialML runtime, neither of which the emulator has. The contract for the device station:
 *
 * 1. Build one SpatialML pipeline at session start (`InitInfo.imageWidth/Height`, e.g. 640 x 360):
 *    `rectifiedVSTAccess(eye = right, cameraMatrixResult, timestamp)` -> `convertColor(RGB -> RGBA)`
 *    -> a global dynamic texture. Read `cameraMatrixResult` once: fx, fy, cx, cy go into [intrinsics]
 *    so that the visor glass at 1 m is W/fx x H/fy (the same line of sight as the picture).
 * 2. Every 1000 / [targetFps] ms: `submit()` then `readbackContentSuspend()` on an IO coroutine; the
 *    RGBA bytes in the SharedMemory are JPEG-encoded (quality 70) on a worker thread and handed to
 *    [CameraSource.Listener.onFrame]; close the SharedMemory after the copy. Never queue: if the encoder
 *    is behind, drop the frame.
 * 3. Pause (worker switches sharing off) = stop submitting and reading back, not only stop sending.
 * 4. Any failure of the pipeline -> [CameraSource.Listener.onError] -> the session shows "画面不可用 · 读回失败".
 *
 * Until it is implemented [available] answers false and [CameraSources.create] falls back to the
 * synthetic source, so the app runs on a device too (with the drawn picture).
 */
class VstReadbackCameraSource(private val context: Context) : CameraSource {
    override val intrinsics = CameraIntrinsics(width = 640, height = 360, fx = 609.5f, fy = 609.5f, cx = 320f, cy = 180f)
    override val targetFps: Int = 15
    override val name: String = "vst"

    override fun start(listener: CameraSource.Listener) {
        JieyanLog.w("CAM vst start requested: readback mode not implemented in this build")
        listener.onError("readback not implemented")
    }

    override fun stop() = Unit

    companion object {
        /** True once the readback pipeline exists on this build and the camera permission is granted. */
        fun available(context: Context): Boolean = IMPLEMENTED && CameraPermission.granted(context)

        private const val IMPLEMENTED = false
    }
}
