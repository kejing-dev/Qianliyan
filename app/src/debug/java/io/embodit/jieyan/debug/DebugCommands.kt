package io.embodit.jieyan.debug

import android.content.Context
import io.embodit.jieyan.domain.DataState
import io.embodit.jieyan.domain.Owner
import io.embodit.jieyan.domain.StrokePoint
import io.embodit.jieyan.platform.JieyanLog
import io.embodit.jieyan.ui.session.SessionEvent
import io.embodit.jieyan.ui.session.debug.Probe
import io.embodit.jieyan.ui.session.scene.PanelIds
import java.io.File
import java.util.Locale

/**
 * Test hooks of the debug build. One log line (tag `Jieyan`) answers every command.
 *
 * Designed states (QA screenshots)
 * - `state` s=S1..S9           jump to the named state of the design package; S3+ connect a simulated
 *                              expert when no browser is connected (the strip reads "专家已连接")
 * - `expert` s=connect|disconnect   simulated expert joins (-> live) / leaves (-> reconnecting)
 * - `stroke` s=expert|worker a=x0,y0,x1,y1,...   a finished stroke on the glass (normalised 0..1); the
 *                              worker's one is also sent to the page. Default: a hook-shaped sample.
 * - `pointer` a=x,y            the expert's pointer dot (no numbers: hide it)
 * - `pen` s=on|off · `share` s=on|off · `fade` s=on|off
 * - `undo` · `clear` · `end` (asks) · `confirm` s=yes|no
 * - `data` s=normal|stale|error   picture state (sticky until `data normal`)
 * - `reconnect` s=on|off|timeout  socket dropped / came back / 30 s passed
 * - `perm` s=denied|granted    camera permission result
 *
 * v3 (picture + voice; `state` also takes S10 S11[:fps] S3d S12 S13 S14[:worker|expert] S15[:insecure|denied]
 * S6a S1u S2m[:both] S2s[:cam|mic|both] S8:perm|timeout|readback|novst)
 * - `mute` s="worker on|off" | "expert on|off"   either side's mute
 * - `speak` s="worker|expert [ms]"   inject a speaking level (S14) without sound
 * - `expertmic` s=ok|insecure|denied   what the expert's browser can do (S15)
 * - `drop` s=audio             close the voice socket only (S6a)   · `audio` s=on|off  mark it linked
 * - `https` s=on|off           LAN https listener (off = S1u)
 * - `never` s=cam|mic|both|off "don't ask again" rows (S2s)   · `mic` s=denied|granted
 * - `fps` s=N                  emulator picture rate (S11 on the page below 8)
 * - `tone` s=ms                 the headset sends a 440 Hz test tone instead of its microphone (device voice path)
 * - `vst` a=W,H                restart the VST readback at W x H (device)   · `vstreset` forget the probed size
 * - `ui` s=<control>           press a control by name: pen undo clear share end confirm_yes confirm_no grant
 *
 * Reading back
 * - `dump`                     `DUMP {json}` (also files/dump.json): design state, phase, pin, expert, strokes, camera
 * - `pose`                     `POSE hmd pos=... fwd=...`
 * - `panels`                   `PANEL id=... pos=... scale=...` for the three pooled panels
 * - `bounds`                   `CTRL panel=... name=... cx= cy= w= h=` for every registered control
 * - `park`                     both lazy-follow panels slide to their rest pose now
 */
internal object DebugCommands {
    private const val NO_HOST = "CMD ignored: no Stage"

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val host = Probe.host ?: return JieyanLog.i(NO_HOST)
        val word = s?.trim()?.lowercase(Locale.US) ?: ""
        val numbers = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        when (cmd.lowercase(Locale.US)) {
            "state" -> host.dispatch(SessionEvent.DebugState(word.ifEmpty { "S1" }))
            "expert" -> host.dispatch(SessionEvent.DebugExpert(word != "disconnect" && word != "off"))
            "stroke" -> {
                val owner = if (word == "worker") Owner.WORKER else Owner.EXPERT
                val pts =
                    if (numbers.size >= 4) numbers.chunked(2).filter { it.size == 2 }.map { StrokePoint(it[0].coerceIn(0f, 1f), it[1].coerceIn(0f, 1f)) }
                    else SAMPLE.map { StrokePoint(it.first, it.second) }
                host.dispatch(SessionEvent.DebugStroke(owner, pts, null, null))
            }
            "pointer" -> host.dispatch(SessionEvent.DebugPointer(if (numbers.size >= 2) StrokePoint(numbers[0].coerceIn(0f, 1f), numbers[1].coerceIn(0f, 1f)) else null))
            "pen" -> host.dispatch(SessionEvent.DebugPen(word == "on"))
            "share" -> host.dispatch(SessionEvent.DebugShare(word == "on"))
            "mute", "speak", "expertmic", "drop", "audio", "https", "never", "mic", "fps", "vstreset", "tone" -> host.dispatch(SessionEvent.DebugVoice(cmd.lowercase(Locale.US), word))
            "vst" -> host.dispatch(SessionEvent.DebugVst(numbers.getOrNull(0)?.toInt() ?: 512, numbers.getOrNull(1)?.toInt() ?: 512))
            "fade" -> host.dispatch(SessionEvent.DebugFade(word == "on"))
            "undo" -> host.dispatch(SessionEvent.UndoClicked)
            "clear" -> host.dispatch(SessionEvent.ClearClicked)
            "end" -> host.dispatch(SessionEvent.EndClicked)
            "confirm" -> host.dispatch(if (word == "no") SessionEvent.EndCancelled else SessionEvent.EndConfirmed)
            "data" -> host.dispatch(SessionEvent.DebugData(DataState.of(word)))
            "reconnect" -> host.dispatch(SessionEvent.DebugReconnect(on = word == "on", timeout = word == "timeout"))
            "perm" -> host.dispatch(SessionEvent.DebugPermission(word != "denied" && word != "off"))
            "ui" -> JieyanLog.i(if (host.press(word)) "UI $word" else "CMD ui unknown or unavailable control '$word'")
            "dump" -> {
                val json = host.dumpJson()
                JieyanLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "pose", "panels", "park" -> host.sceneLines(cmd.lowercase(Locale.US)).forEach { JieyanLog.i(it) }
            "bounds" -> {
                for (panel in PanelIds.pool) {
                    val size = Probe.sizeOf(panel)
                    JieyanLog.i("PANELPX id=$panel size_px=${size?.first ?: 0}x${size?.second ?: 0}")
                    for ((name, b) in Probe.controlsOf(panel)) {
                        JieyanLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, name, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            else -> JieyanLog.i("CMD unknown '$cmd'")
        }
    }

    /** A hook-shaped sample stroke around the machine's lamp in the synthetic picture. */
    private val SAMPLE =
        listOf(
            0.33f to 0.52f, 0.35f to 0.50f, 0.38f to 0.50f, 0.41f to 0.52f, 0.43f to 0.56f, 0.43f to 0.62f,
            0.41f to 0.67f, 0.38f to 0.70f, 0.35f to 0.70f, 0.32f to 0.68f, 0.30f to 0.64f, 0.30f to 0.58f, 0.32f to 0.54f,
        )
}
