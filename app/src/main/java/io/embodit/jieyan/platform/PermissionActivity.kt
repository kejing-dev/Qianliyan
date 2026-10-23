package io.embodit.jieyan.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Transparent, short-lived Activity that asks for the camera and microphone permissions (design v3 C9:
 * both at launch, or from the lobby's 授权 keys). A Stage app has no Activity to call
 * `requestPermissions` on, so this one does; the answers go to [CameraPermission.listener] and the
 * Activity finishes at once. Extra [EXTRA_WHICH] narrows the request to one permission.
 */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = intent.getStringArrayExtra(EXTRA_WHICH) ?: CameraPermission.ALL
        val missing = which.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            CameraPermission.report(this, emptyList(), emptySet())
            finish()
            return
        }
        CameraPermission.markAsked(this, missing)
        requestPermissions(missing.toTypedArray(), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST) {
            // denied and no rationale any more = "don't ask again": the lobby offers 去设置 then (S2s)
            val never = permissions.filterIndexed { i, p -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(p) }.toSet()
            CameraPermission.report(this, permissions.toList(), never)
        }
        finish()
    }

    companion object {
        private const val REQUEST = 41
        const val EXTRA_WHICH = "which"
    }
}

/** What the app knows about the camera and microphone permissions; every answer is pushed to [listener]. */
object CameraPermission {
    val ALL = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    data class Result(val camera: Boolean, val mic: Boolean, val cameraNever: Boolean, val micNever: Boolean)

    @Volatile var listener: ((Result) -> Unit)? = null

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun micGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True when the permission was refused for good (stored at the last answer; cleared once granted). */
    fun never(context: Context, permission: String): Boolean =
        prefs(context).getBoolean("never:$permission", false) && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED

    fun request(context: Context, vararg which: String) {
        val intent =
            Intent(context, PermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .apply { if (which.isNotEmpty()) putExtra(PermissionActivity.EXTRA_WHICH, which) }
        runCatching { context.startActivity(intent) }.onFailure { JieyanLog.e("PERM request failed: $it", it) }
    }

    /** S2s: the system's app permission page (the only way back after "don't ask again"). */
    fun openSettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { JieyanLog.e("PERM settings failed: $it", it) }
    }

    fun current(context: Context): Result =
        Result(
            camera = granted(context),
            mic = micGranted(context),
            cameraNever = never(context, Manifest.permission.CAMERA),
            micNever = never(context, Manifest.permission.RECORD_AUDIO),
        )

    internal fun markAsked(context: Context, which: List<String>) {
        prefs(context).edit().apply { which.forEach { putBoolean("asked:$it", true) } }.apply()
    }

    internal fun report(context: Context, asked: List<String>, never: Set<String>) {
        prefs(context).edit().apply { asked.forEach { putBoolean("never:$it", it in never) } }.commit()
        val r = current(context)
        JieyanLog.i("PERM camera granted=${r.camera} never=${r.cameraNever} mic granted=${r.mic} never=${r.micNever}")
        listener?.invoke(r)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("perm", Context.MODE_PRIVATE)
}
