package io.embodit.jieyan.platform

/**
 * Seam for the debug build's command hooks. On a headset adb broadcasts are not always delivered (they
 * queue while the display is off), so the debug build also takes the same commands over HTTP:
 * `GET /debug?cmd=NAME&s=TEXT&a=NUMBERS` on the assist server. [handler] is null in release builds, and
 * the route answers 404 then.
 */
object DebugBridge {
    @Volatile var handler: ((cmd: String, s: String?, a: String?) -> Unit)? = null
}
