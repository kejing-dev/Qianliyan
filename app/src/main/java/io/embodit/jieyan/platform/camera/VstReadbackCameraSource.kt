package io.embodit.jieyan.platform.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.pico.spatial.ml.readback.readbackContentSuspend
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.SpatialMLException
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.platform.CameraPermission
import io.embodit.jieyan.platform.JieyanLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs

/**
 * The headset's camera through SpatialML **Readback Mode** (design v3 §1.2, the only sanctioned way for
 * an app to read VST pixels on PICO OS 6.1). One graph, built once per [start], submitted every
 * 1000 / [targetFps] ms:
 *
 * ```
 * rectifiedVSTAccess(right = rgb (local R8G8B8_U), left = null, timestamp = stamp, camera matrix = camK)
 * convertColor(RGB_TO_RGBA, rgb, frame)          frame, stamp, camK: global tensors (readback-able)
 * ```
 *
 * After each submit `stamp` is read back; an unchanged stamp is not a new camera frame (it is neither
 * sent nor counted, which also makes the fps the page shows real). A new one is followed by the readback
 * of `frame` (RGBA = the byte order of `ARGB_8888`, so one `copyPixelsFromBuffer`) and a JPEG encode.
 * `camK` is read once, after the first frame, into [intrinsics].
 *
 * Grounding: spatial-ml_workflows_read-data-back-to-the-app.md (global tensors only, `TensorContent.use`),
 * spatial-ml_reference_core-api.md (`InitInfo` with a DISABLED container in Readback Mode),
 * spatial-ml_reference_tensor-types-and-enums.md (RGB -> `RGB_TO_RGBA` before readback),
 * spatial-ml_workflows_async-pipeline-patterns.md (build once, 5 consecutive submit failures stop the loop).
 * The SDK has no explicit release call for sessions / pipelines / tensors (their handles are freed by the
 * `BaseHandle` cleaner): [stop] cancels the loop, closes the open `TensorContent` (the `use` block) and
 * drops every reference, children before the session.
 *
 * Needs `android.permission.CAMERA`; the session checks it before it starts this source.
 */
class VstReadbackCameraSource(
    private val context: Context,
    val width: Int = DEFAULT_W,
    val height: Int = DEFAULT_H,
) : CameraSource {
    @Volatile
    override var intrinsics: CameraIntrinsics = guessIntrinsics(width, height)
        private set

    override val targetFps: Int = FPS
    override val name: String = "vst"

    /** JPEG quality; lowered by [degrade] when the measured rate stays low (design §1.3). */
    @Volatile var quality: Int = QUALITY_HIGH

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** Frames delivered, submits, and the time spent in readback + encode (for the fps / latency log). */
    @Volatile var stats = Stats()
        private set

    data class Stats(val frames: Int = 0, val submits: Int = 0, val dupes: Int = 0, val readMs: Long = 0, val encodeMs: Long = 0, val bytes: Long = 0)

    override fun start(listener: CameraSource.Listener) {
        if (job != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch { run(listener) }
        JieyanLog.i("CAM vst start ${width}x$height fps=$FPS q=$quality")
    }

    override fun stop() {
        val s = scope ?: return
        scope = null
        job = null
        s.cancel()
        val st = stats
        JieyanLog.i("CAM vst stop frames=${st.frames} submits=${st.submits} dupes=${st.dupes}")
    }

    fun degrade() {
        if (quality > QUALITY_LOW) {
            quality = QUALITY_LOW
            JieyanLog.i("CAM vst quality -> $quality")
        }
    }

    private suspend fun run(listener: CameraSource.Listener) {
        val t0 = SystemClock.elapsedRealtime()
        if (!CameraPermission.granted(context)) {
            JieyanLog.w("CAM vst no camera permission")
            listener.onError(REASON_PERMISSION)
            return
        }
        var graph = buildWithRetry(listener) ?: return
        JieyanLog.i("CAM vst graph ready in ${SystemClock.elapsedRealtime() - t0} ms")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        val out = ByteArrayOutputStream(128 * 1024)
        val period = 1000L / FPS
        var failures = 0
        var lastStamp: List<Int>? = null
        var graphAtMs = SystemClock.elapsedRealtime()
        var rebuilds = 0
        var intrinsicsRead = false
        var st = Stats()
        var lastLogged = st
        var lastLogMs = SystemClock.elapsedRealtime()
        try {
            while (scope?.isActive == true && failures < MAX_FAILURES) {
                val loopStart = SystemClock.elapsedRealtime()
                // the camera service can stay "opening, but not ready" after a failed configure (Swan, first
                // session after launch): no new stamp at all for 2.5 s -> a new session, at most twice
                if (st.frames == 0 && loopStart - graphAtMs > NO_FRAME_REBUILD_MS && rebuilds < MAX_REBUILDS) {
                    rebuilds++
                    JieyanLog.w("CAM vst no frame ${loopStart - graphAtMs} ms after the graph: new session ($rebuilds/$MAX_REBUILDS)")
                    delay(BUILD_RETRY_MS)
                    graph = buildWithRetry(listener) ?: return
                    graphAtMs = SystemClock.elapsedRealtime()
                    continue
                }
                try {
                    graph.pipeline.submit(emptyMap(), null, null)
                    st = st.copy(submits = st.submits + 1)
                    val stamp = graph.stamp.readbackContentSuspend().use { readInts(it.buffer) }
                    if (stamp == lastStamp || stamp.all { it == 0 }) {
                        st = st.copy(dupes = st.dupes + 1)
                    } else {
                        lastStamp = stamp
                        val readStart = SystemClock.elapsedRealtime()
                        graph.frame.readbackContentSuspend().use { content ->
                            val buf = content.buffer
                            buf.rewind()
                            if (st.frames == 0) JieyanLog.i("CAM vst first frame bytes=${buf.remaining()} expect=${width * height * 4} alpha0=${buf.get(3).toInt() and 0xFF} stamp=$stamp")
                            bitmap.copyPixelsFromBuffer(buf)
                        }
                        val readMs = SystemClock.elapsedRealtime() - readStart
                        bitmap.setHasAlpha(false)
                        val encStart = SystemClock.elapsedRealtime()
                        out.reset()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        val jpeg = out.toByteArray()
                        val encMs = SystemClock.elapsedRealtime() - encStart
                        st = st.copy(frames = st.frames + 1, readMs = st.readMs + readMs, encodeMs = st.encodeMs + encMs, bytes = st.bytes + jpeg.size)
                        if (!intrinsicsRead) {
                            intrinsicsRead = true
                            readIntrinsics(graph.camK)?.let {
                                intrinsics = it
                                listener.onIntrinsics(it)
                            }
                        }
                        listener.onFrame(jpeg, readStart)
                    }
                    stats = st
                    failures = 0
                    if (loopStart - lastLogMs >= STATS_LOG_MS) {
                        logStats(st, lastLogged, loopStart - lastLogMs)
                        lastLogged = st
                        lastLogMs = loopStart
                    }
                } catch (e: SpatialMLException) {
                    failures++
                    JieyanLog.w("CAM vst submit/readback failed ($failures/$MAX_FAILURES): ${e.message}")
                }
                val spent = SystemClock.elapsedRealtime() - loopStart
                delay((period - spent).coerceAtLeast(1L))
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            JieyanLog.e("CAM vst loop failed: $t", t)
            listener.onError(REASON_READBACK)
            return
        } finally {
            bitmap.recycle()
        }
        if (failures >= MAX_FAILURES) listener.onError(REASON_READBACK)
    }

    /** Builds the graph; one retry after [BUILD_RETRY_MS] (the first `configure` after launch can fail: Secure MR "[CAMERA ERROR]: camera >>> configure => -1"). */
    private suspend fun buildWithRetry(listener: CameraSource.Listener): Graph? {
        for (attempt in 1..BUILD_ATTEMPTS) {
            try {
                return build().also { listener.onGraphBuilt() }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                JieyanLog.e("CAM vst build failed (attempt $attempt/$BUILD_ATTEMPTS): $t", if (attempt == BUILD_ATTEMPTS) t else null)
                if (attempt == BUILD_ATTEMPTS) {
                    listener.onError(if (t is NoVst) REASON_NO_VST else REASON_READBACK)
                    return null
                }
                delay(BUILD_RETRY_MS)
            }
        }
        return null
    }

    private fun logStats(now: Stats, before: Stats, windowMs: Long) {
        val frames = now.frames - before.frames
        val fps = frames * 1000f / windowMs.coerceAtLeast(1L)
        val read = if (frames > 0) (now.readMs - before.readMs) / frames else 0
        val enc = if (frames > 0) (now.encodeMs - before.encodeMs) / frames else 0
        val kb = if (frames > 0) (now.bytes - before.bytes) / frames / 1024 else 0
        JieyanLog.i(String.format(Locale.US, "CAM vst stats fps=%.1f frames=%d submits=%d dupes=%d readMs=%d encMs=%d kb=%d q=%d", fps, frames, now.submits - before.submits, now.dupes - before.dupes, read, enc, kb, quality))
    }

    /** The timestamp tensor: one element of 4 INT32 channels (spatial-ml_reference_core-api TimeStampInitInfo). */
    private fun readInts(buf: java.nio.ByteBuffer): List<Int> {
        val b = buf.duplicate().order(java.nio.ByteOrder.nativeOrder())
        b.rewind()
        val out = ArrayList<Int>(4)
        while (b.remaining() >= 4 && out.size < 4) out += b.getInt()
        return out
    }

    private class NoVst(msg: String) : Exception(msg)

    private class Graph(val session: SpatialMLSession, val pipeline: Pipeline, val frame: GlobalTensor, val stamp: GlobalTensor, val camK: GlobalTensor)

    private suspend fun build(): Graph {
        val instance = instance(context)
        withTimeoutOrNull(INSTANCE_TIMEOUT_MS) { while (!instance.ready) delay(50) } ?: throw NoVst("SpatialML instance not ready in $INSTANCE_TIMEOUT_MS ms")
        val session =
            instance.createSession(
                SpatialMLSession.InitInfo(width, height, 0, 0, 0, SpatialMLSession.ContainerType.DISABLED),
            ) ?: throw IllegalStateException("createSession returned null")
        val frame = session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8A8_U, intArrayOf(height, width)))
        val stamp = session.newGlobalTensor(Tensor.TimeStampInitInfo())
        val camK = session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(3, 3)))
        val pipeline =
            session.newPipeline().apply {
                val rgb = newLocalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8_U, intArrayOf(height, width)))
                rectifiedVSTAccess(rgb, null, stamp, camK)
                convertColor(Pipeline.ColorConversion.RGB_TO_RGBA, rgb, frame)
            }
        return Graph(session, pipeline, frame, stamp, camK)
    }

    private suspend fun readIntrinsics(camK: GlobalTensor): CameraIntrinsics? =
        runCatching {
            camK.readbackContentSuspend().use { k ->
                val m = k[0..8].asFloatArray()
                JieyanLog.i("CAM vst camK=${m.joinToString(",") { String.format(Locale.US, "%.2f", it) }}")
                val fx = m[0]
                val fy = m[4]
                if (fx <= 1f || fy <= 1f) return@use null
                val squareness = abs(fx - fy) / fx
                JieyanLog.i(String.format(Locale.US, "CAM vst intrinsics %dx%d fx=%.1f fy=%.1f cx=%.1f cy=%.1f |fx-fy|/fx=%.3f glass=%.3fx%.3f m", width, height, fx, fy, m[2], m[5], squareness, width / fx, height / fy))
                CameraIntrinsics(width = width, height = height, fx = fx, fy = fy, cx = m[2], cy = m[5])
            }
        }.onFailure { JieyanLog.w("CAM vst camK readback failed: $it") }.getOrNull()

    companion object {
        const val DEFAULT_W = 960
        const val DEFAULT_H = 540
        const val FPS = 15
        const val QUALITY_HIGH = 70
        const val QUALITY_LOW = 55
        private const val MAX_FAILURES = 5
        private const val BUILD_ATTEMPTS = 2
        private const val BUILD_RETRY_MS = 800L
        private const val NO_FRAME_REBUILD_MS = 2_500L
        private const val MAX_REBUILDS = 2
        private const val STATS_LOG_MS = 5_000L
        private const val INSTANCE_TIMEOUT_MS = 4_000L

        const val REASON_NO_VST = "novst"
        const val REASON_PERMISSION = "perm"
        const val REASON_READBACK = "readback"

        @Volatile private var shared: SpatialMLInstance? = null

        /** The process-wide SpatialML instance (create once and reuse, spatial-ml_reference_core-api.md). */
        @Synchronized
        fun instance(context: Context): SpatialMLInstance = shared ?: SpatialMLInstance.create(context.applicationContext).also { shared = it }

        /** Until the first camK readback: a 60 x 34 degree guess so the glass has a size. */
        fun guessIntrinsics(w: Int, h: Int): CameraIntrinsics {
            val f = w / 1.05f
            return CameraIntrinsics(width = w, height = h, fx = f, fy = f, cx = w / 2f, cy = h / 2f)
        }
    }
}
