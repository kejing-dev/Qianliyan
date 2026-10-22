package io.embodit.jieyan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.jieyan.platform.JieyanLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.jieyan.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s emulator-5560 shell "am broadcast -a io.embodit.jieyan.DEBUG -p io.embodit.jieyan --es cmd state --es s S4"
 *
 * Extras: `cmd` = command name, `s` = text argument, `a` = comma-separated numbers. For convenience
 * the text argument may also follow the name inside `cmd` (`--es cmd "state S4"`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0]
        val s = intent.getStringExtra("s") ?: parts.getOrNull(1)
        val a = intent.getStringExtra("a")
        JieyanLog.i("CMD recv '$cmd' s='$s' a='$a'")
        // broadcasts arrive on the main thread, which is where the ViewModel and the scene live
        DebugCommands.run(context.applicationContext, cmd, s, a)
    }
}
