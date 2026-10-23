package io.embodit.jieyan.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format between headset and expert page (design networking.md §5). JSON text frames carry `t`;
 * the picture goes as binary frames `[u32 seq][u64 tsMs][JPEG]`. All coordinates are normalised to the
 * camera image (0..1). The headset is the authority: the page only mirrors `state`.
 *
 * expert -> headset: hello{pin, session?} · stroke.begin/point/end · undo{id} · clear · unclear ·
 *                    pointer{x,y} · pen{on} (not used by the page) · fade{on} · end · ping{ts} ·
 *                    mute{on} · mic{state: ok|insecure|denied} · video.retry
 * headset -> expert: welcome{session,imageW,imageH,fx,fy,fps,link,demo,https} · reject{reason} · state{...} ·
 *                    stroke.* · undo · clear · unclear · pointer · pen{on} · fade{on} · end · pong{ts,hs} ·
 *                    video{imageW,imageH,fx,fy,fps,q} (once a second while the picture runs)
 *
 * Voice runs on the second socket `/audio?session=…`: binary `[u16 seq][u8 flags][PCM16 LE x 320]` both ways.
 */
object Protocol {
    fun stroke(stroke: Stroke): JSONObject =
        JSONObject()
            .put("id", stroke.id)
            .put("owner", stroke.owner.wire)
            .put("color", stroke.color)
            .put("width", stroke.width.toDouble())
            .put("pts", points(stroke.points))
            .apply { stroke.endedAtMs?.let { put("endedAt", it) } }

    fun points(points: List<StrokePoint>): JSONArray {
        val arr = JSONArray()
        for (p in points) arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
        return arr
    }

    fun parsePoints(arr: JSONArray?): List<StrokePoint> {
        if (arr == null) return emptyList()
        val out = ArrayList<StrokePoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            out += StrokePoint(p.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f), p.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f))
        }
        return out
    }

    fun parseStroke(o: JSONObject, nowMs: Long): Stroke =
        Stroke(
            id = o.optString("id", "s${nowMs}"),
            owner = Owner.of(o.optString("owner")),
            color = o.optString("color", "#FFE14D"),
            width = o.optDouble("width", 6.0).toFloat().coerceIn(1f, 40f),
            points = parsePoints(o.optJSONArray("pts")),
            endedAtMs = if (o.has("endedAt")) o.optLong("endedAt") else null,
        )

    fun strokeBegin(stroke: Stroke): JSONObject = stroke(stroke).put("t", "stroke.begin")

    fun strokePoint(id: String, points: List<StrokePoint>): JSONObject = JSONObject().put("t", "stroke.point").put("id", id).put("pts", points(points))

    fun strokeEnd(id: String): JSONObject = JSONObject().put("t", "stroke.end").put("id", id)

    fun undo(id: String, owner: Owner): JSONObject = JSONObject().put("t", "undo").put("id", id).put("owner", owner.wire)

    fun clear(by: Owner): JSONObject = JSONObject().put("t", "clear").put("by", by.wire)

    fun unclear(strokes: List<Stroke>): JSONObject = JSONObject().put("t", "unclear").put("strokes", JSONArray().apply { strokes.forEach { put(stroke(it)) } })

    fun pointer(x: Float, y: Float): JSONObject = JSONObject().put("t", "pointer").put("x", x.toDouble()).put("y", y.toDouble())

    fun pen(on: Boolean): JSONObject = JSONObject().put("t", "pen").put("on", on)

    fun fade(on: Boolean): JSONObject = JSONObject().put("t", "fade").put("on", on)

    fun end(reason: String): JSONObject = JSONObject().put("t", "end").put("reason", reason)

    /** [hs]: the headset's `elapsedRealtime` when answering (the page estimates the clock offset for the latency). */
    fun pong(ts: Long, hs: Long): JSONObject = JSONObject().put("t", "pong").put("ts", ts).put("hs", hs)

    fun welcome(session: String, intrinsics: CameraIntrinsics, fps: Int, link: String, demo: Boolean, https: Boolean): JSONObject =
        intrinsicsInto(JSONObject().put("t", "welcome").put("session", session), intrinsics)
            .put("fps", fps)
            .put("link", link)
            .put("demo", demo)
            .put("https", https)

    fun video(intrinsics: CameraIntrinsics, fps: Float, quality: Int): JSONObject =
        intrinsicsInto(JSONObject().put("t", "video"), intrinsics).put("fps", fps.toDouble()).put("q", quality)

    private fun intrinsicsInto(o: JSONObject, k: CameraIntrinsics): JSONObject =
        o.put("imageW", k.width).put("imageH", k.height).put("fx", k.fx.toDouble()).put("fy", k.fy.toDouble())

    fun reject(reason: String): JSONObject = JSONObject().put("t", "reject").put("reason", reason)

    /** The voice part of `state`: both mute flags, whether the worker's microphone is allowed. */
    data class VoiceState(val workerMuted: Boolean, val expertMuted: Boolean, val workerMic: Boolean)

    /** Full state, sent on connect / reconnect and whenever share, pen, data, fade or the voice flags change. */
    fun state(phase: Phase, share: Boolean, pen: Boolean, data: DataState, reason: VideoError, fade: Boolean, strokes: List<Stroke>, voice: VoiceState): JSONObject =
        JSONObject()
            .put("t", "state")
            .put("phase", phase.name.lowercase())
            .put("share", share)
            .put("pen", pen)
            .put("data", data.wire)
            .put("reason", reason.wire)
            .put("fade", fade)
            .put("strokes", JSONArray().apply { strokes.forEach { put(stroke(it)) } })
            .put("workerMuted", voice.workerMuted)
            .put("expertMuted", voice.expertMuted)
            .put("workerMic", voice.workerMic)

    /** Binary picture frame: `[u32 seq][u64 tsMs][jpeg]`, big-endian. */
    fun frame(seq: Int, tsMs: Long, jpeg: ByteArray): ByteArray {
        val out = ByteArray(12 + jpeg.size)
        out[0] = (seq ushr 24).toByte(); out[1] = (seq ushr 16).toByte(); out[2] = (seq ushr 8).toByte(); out[3] = seq.toByte()
        for (i in 0 until 8) out[4 + i] = (tsMs ushr (56 - 8 * i)).toByte()
        System.arraycopy(jpeg, 0, out, 12, jpeg.size)
        return out
    }
}
