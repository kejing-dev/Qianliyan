package io.embodit.jieyan.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.embodit.jieyan.platform.DebugBridge
import io.embodit.jieyan.platform.JieyanLog

/** Debug builds only: installs [DebugBridge.handler] at process start (commands run on the main thread). */
class DebugInit : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        val main = Handler(Looper.getMainLooper())
        DebugBridge.handler = { cmd, s, a ->
            main.post {
                JieyanLog.i("CMD http '$cmd' s='$s' a='$a'")
                DebugCommands.run(app, cmd, s, a)
            }
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
