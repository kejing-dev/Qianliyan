package io.embodit.jieyan.domain

/** Who drew a stroke. The worker's pen is always chalk blue; the expert picks yellow / white / pink. */
enum class Owner(val wire: String) {
    EXPERT("expert"),
    WORKER("worker");

    companion object {
        fun of(wire: String?): Owner = if (wire == WORKER.wire) WORKER else EXPERT
    }
}

/** A point of a stroke in normalised camera-image coordinates (0..1, origin top-left). */
data class StrokePoint(val x: Float, val y: Float)

/**
 * One chalk line. [width] is in pixels of the 1280 x 720 reference frame (the expert page draws at the
 * same reference). [endedAtMs] is set when the stroke is finished; with the fade switch on, a stroke
 * fades from 8 s after its end over 2 s ([FADE_START_MS], [FADE_MS]).
 */
data class Stroke(
    val id: String,
    val owner: Owner,
    val color: String,
    val width: Float,
    val points: List<StrokePoint>,
    val endedAtMs: Long? = null,
) {
    /** 1 while fully visible, 0 when gone; between while fading. */
    fun alpha(nowMs: Long, fadeOn: Boolean): Float {
        if (!fadeOn) return 1f
        val ended = endedAtMs ?: return 1f
        val since = nowMs - ended
        return when {
            since < FADE_START_MS -> 1f
            since >= FADE_START_MS + FADE_MS -> 0f
            else -> 1f - (since - FADE_START_MS).toFloat() / FADE_MS
        }
    }

    companion object {
        const val FADE_START_MS = 8_000L
        const val FADE_MS = 2_000L
    }
}

/** The expert's pointer (hover) on the image: a yellow dot that goes out 700 ms after the last update. */
data class Pointer(val x: Float, val y: Float, val atMs: Long) {
    companion object {
        const val LIFETIME_MS = 700L
    }
}

/** Where the session stands (headset view; the expert page mirrors it). */
enum class Phase {
    /** S1 / S2: waiting for the expert, PIN on the lobby card. */
    LOBBY,

    /** S3 / S4 / S7 / S8: sharing, expert connected. */
    LIVE,

    /** S5: the worker switched sharing off. */
    PAUSED,

    /** S6: the socket dropped; the expert has 30 s to come back. */
    RECONNECTING,
}

/**
 * Whether the picture reaching the expert is current, starting (S10: no first frame yet), stale (no
 * frame for 3 s) or unavailable (S8, with a [VideoError] reason).
 */
enum class DataState(val wire: String) {
    NORMAL("normal"),
    STARTING("starting"),
    STALE("stale"),
    ERROR("error");

    companion object {
        fun of(wire: String?): DataState = entries.firstOrNull { it.wire == wire } ?: NORMAL
    }
}

/** Why the picture is unavailable (S8, design v3 §1.4). */
enum class VideoError(val wire: String) {
    PERMISSION("perm"),
    TIMEOUT("timeout"),
    READBACK("readback"),
    NO_VST("novst");

    companion object {
        fun of(wire: String?): VideoError = entries.firstOrNull { it.wire == wire } ?: READBACK
    }
}

/** What the expert's browser can do with its microphone (design v3 C14: S15 on the headset). */
enum class ExpertMic(val wire: String) {
    UNKNOWN("unknown"),
    OK("ok"),
    /** http on a LAN address: the browser gives no microphone (E4). */
    INSECURE("insecure"),
    /** The browser or the user refused it (E5). */
    DENIED("denied");

    val canTalk: Boolean
        get() = this == OK || this == UNKNOWN

    companion object {
        fun of(wire: String?): ExpertMic = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** Camera intrinsics of the shared image: the visor glass at 1 m is W/fx wide and H/fy high. */
data class CameraIntrinsics(val width: Int, val height: Int, val fx: Float, val fy: Float, val cx: Float, val cy: Float) {
    val glassWidthM: Float
        get() = width / fx

    val glassHeightM: Float
        get() = height / fy
}

/** 4-digit PIN; one per session, a new one after every end or reconnect time-out. */
object Pin {
    fun random(): String = String.format(java.util.Locale.US, "%04d", java.security.SecureRandom().nextInt(10_000))
}
