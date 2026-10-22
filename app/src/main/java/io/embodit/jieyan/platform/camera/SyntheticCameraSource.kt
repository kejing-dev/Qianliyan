package io.embodit.jieyan.platform.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.embodit.jieyan.domain.CameraIntrinsics
import io.embodit.jieyan.platform.JieyanLog
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic camera: a drawn "workbench" (grid, a machine with a status lamp, a sweeping arm, a
 * millisecond clock and the frame number) at [FPS] frames per second, JPEG quality [QUALITY]. It makes
 * the whole sync loop checkable on the emulator: the clock in the picture is what the expert page shows,
 * the lamp and the arm move, and a stroke drawn on the page lands on the glass at the same normalised
 * spot. Intrinsics are fixed so that the glass at 1 m is 1.05 x 0.59 m (about 55 x 33 degrees).
 */
class SyntheticCameraSource(@Suppress("unused") private val context: Context) : CameraSource {
    override val intrinsics = CameraIntrinsics(width = W, height = H, fx = FX, fy = FX, cx = W / 2f, cy = H / 2f)
    override val targetFps: Int = FPS
    override val name: String = "synthetic"

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: CameraSource.Listener? = null
    private var seq = 0
    private val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val out = ByteArrayOutputStream(64 * 1024)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 22f }
    private val bg = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFF3A4046.toInt(), 0xFF1C2024.toInt(), Shader.TileMode.CLAMP)
    private val startMs = SystemClock.elapsedRealtime()

    private val tick =
        object : Runnable {
            override fun run() {
                val h = handler ?: return
                val t0 = SystemClock.elapsedRealtime()
                draw(t0)
                out.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                listener?.onFrame(out.toByteArray(), t0)
                val spent = SystemClock.elapsedRealtime() - t0
                h.postDelayed(this, (PERIOD_MS - spent).coerceAtLeast(1L))
            }
        }

    override fun start(listener: CameraSource.Listener) {
        if (thread != null) return
        this.listener = listener
        val t = HandlerThread("jieyan-synth-cam").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
        JieyanLog.i("CAM synthetic start ${W}x$H fps=$FPS q=$QUALITY")
    }

    override fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        listener = null
        JieyanLog.i("CAM synthetic stop frames=$seq")
    }

    private fun draw(nowMs: Long) {
        seq++
        val t = (nowMs - startMs) / 1000f
        paint.shader = bg
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        // floor grid (perspective-ish horizontal lines) so the picture reads as a room
        paint.color = 0x33FFFFFF
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        for (i in 0..8) {
            val y = H * 0.55f + i * i * 4.5f
            canvas.drawLine(0f, y, W.toFloat(), y, paint)
        }
        for (i in -6..6) {
            canvas.drawLine(W / 2f + i * 30f, H * 0.55f, W / 2f + i * 140f, H.toFloat(), paint)
        }
        // the machine: a cabinet with a status lamp that blinks every second
        paint.style = Paint.Style.FILL
        paint.color = 0xFF4E565E.toInt()
        canvas.drawRoundRect(RectF(W * 0.18f, H * 0.28f, W * 0.46f, H * 0.78f), 10f, 10f, paint)
        paint.color = 0xFF2B3136.toInt()
        canvas.drawRoundRect(RectF(W * 0.21f, H * 0.33f, W * 0.43f, H * 0.52f), 6f, 6f, paint)
        paint.color = if ((t * 2).toInt() % 2 == 0) 0xFFFF3B30.toInt() else 0xFF5A2320.toInt()
        canvas.drawCircle(W * 0.40f, H * 0.62f, 9f, paint)
        paint.color = 0xFF7CF2A3.toInt()
        canvas.drawCircle(W * 0.35f, H * 0.62f, 9f, paint)
        // the arm: sweeps left-right so that motion is visible on both ends
        val cx = W * 0.70f
        val cy = H * 0.50f
        val a = sin(t * 0.8f) * 0.9f
        paint.color = 0xFFB8BEC5.toInt()
        paint.strokeWidth = 14f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy + 90f, cx + 120f * sin(a), cy + 90f - 150f * cos(a), paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFE14D.toInt()
        canvas.drawCircle(cx + 120f * sin(a), cy + 90f - 150f * cos(a), 12f, paint)
        paint.color = 0xFF6B7278.toInt()
        canvas.drawRoundRect(RectF(cx - 60f, cy + 80f, cx + 60f, cy + 120f), 8f, 8f, paint)
        // clock + frame number: what the expert page must show (end-to-end delay check)
        val ms = nowMs - startMs
        text.color = 0xCCFFFFFF.toInt()
        canvas.drawText(String.format(Locale.US, "SYNTH %02d:%02d.%03d  #%d", ms / 60000, (ms / 1000) % 60, ms % 1000, seq), 14f, H - 16f, text)
        text.color = 0x99FFFFFF.toInt()
        canvas.drawText("no VST on this build", W - 230f, H - 16f, text)
    }

    companion object {
        const val W = 640
        const val H = 360
        const val FPS = 12
        const val QUALITY = 70
        private const val PERIOD_MS = 1000L / FPS

        /** fx = fy: the glass at 1 m is W/fx = 1.05 m wide and H/fy = 0.59 m high. */
        const val FX = 609.5f
    }
}
