package io.embodit.jieyan.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent, short-lived Activity that asks for the camera permission. A Stage app has no Activity
 * to call `requestPermissions` on, so the lobby card's "授权" key starts this one; the answer goes to
 * [CameraPermission.listener] and the Activity finishes at once.
 */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CameraPermission.granted(this)) {
            CameraPermission.report(true)
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) {
            CameraPermission.report(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
        }
        finish()
    }

    companion object {
        private const val REQUEST = 41
    }
}

/** What the app knows about the camera permission; the answer of a request is pushed to [listener]. */
object CameraPermission {
    @Volatile var listener: ((Boolean) -> Unit)? = null

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun request(context: Context) {
        val intent = Intent(context, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { context.startActivity(intent) }.onFailure { JieyanLog.e("PERM request failed: $it", it) }
    }

    internal fun report(granted: Boolean) {
        JieyanLog.i("PERM camera granted=$granted")
        listener?.invoke(granted)
    }
}
