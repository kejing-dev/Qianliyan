package io.embodit.jieyan.platform.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import io.embodit.jieyan.platform.JieyanLog
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The headset end of the call (design v3 §2.1): `AudioRecord(VOICE_COMMUNICATION)` 16 kHz mono PCM16 with
 * the platform's echo canceller and noise suppressor (only when `isAvailable`), 20 ms packets out;
 * `AudioTrack(USAGE_VOICE_COMMUNICATION)` on the same audio session (the canceller's reference) behind a
 * 60 ms jitter buffer that never holds more than 200 ms; `MODE_IN_COMMUNICATION` while the call runs,
 * and the built-in speaker chosen with `setCommunicationDevice` (the default route of a headset in call
 * mode is not documented).
 *
 * Packet (both directions): `[u16 seq, big-endian][u8 flags][PCM16 little-endian x 320]`; flags bit 0 =
 * the sender is muted (then the packet has no samples and goes every 200 ms only to carry the flag).
 *
 * Two threads (capture, playback), both stopped and every audio object released in [stop]; the audio
 * mode and the communication device are restored there too.
 */
class VoiceEngine(private val context: Context, private val listener: Listener) {
    interface Listener {
        /** A packet for the expert. Capture thread. */
        fun onPacket(packet: ByteArray)

        /** Smoothed levels 0..1 of the worker's microphone and of the expert's voice, ~20 per second. Capture thread. */
        fun onLevels(self: Float, remote: Float)
    }

    data class Report(val aec: Boolean?, val ns: Boolean?, val device: String?, val mic: Boolean)

    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var running = false
    @Volatile var muted = false

    /** Debug: until this time the captured microphone frames are replaced by a 440 Hz test tone (proves the headset -> page path). */
    @Volatile var toneUntilMs = 0L
    private var tonePhase = 0.0

    /** The expert's mute flag as last seen in a packet. */
    @Volatile var remoteMuted = false
        private set

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private var previousMode = AudioManager.MODE_NORMAL

    private val queue = ArrayDeque<ShortArray>()
    private val lock = Object()
    private var primed = false

    @Volatile private var remoteLevel = 0f
    @Volatile private var lastRemoteAtMs = 0L

    /** Counters for the log / dump: packets in / out, dropped to keep the buffer ≤ 200 ms, underruns. */
    @Volatile var stats = IntArray(4)
        private set

    var report: Report? = null
        private set

    val micGranted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Report {
        if (running) return report ?: Report(null, null, null, micGranted)
        running = true
        stats = IntArray(4)
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val deviceOk = speaker != null && runCatching { audio.setCommunicationDevice(speaker) }.getOrDefault(false)
        val device = audio.communicationDevice?.let { "${typeName(it.type)}${if (deviceOk) "" else "(default)"}" }

        val mic = micGranted
        var aecOn: Boolean? = null
        var nsOn: Boolean? = null
        val sessionId: Int
        if (mic) {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 8))
            record = r
            sessionId = r.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
                aecOn = aec?.enabled == true
            } else {
                aecOn = false
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
                nsOn = ns?.enabled == true
            } else {
                nsOn = false
            }
        } else {
            sessionId = audio.generateAudioSessionId()
        }
        val t =
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(sessionId)
                .build()
        track = t
        t.play()
        playThread = Thread({ playLoop(t) }, "jieyan-voice-play").apply { start() }
        record?.let { r ->
            r.startRecording()
            captureThread = Thread({ captureLoop(r) }, "jieyan-voice-capture").apply { start() }
        } ?: run {
            // no microphone: still tick the levels and the mute flag so the page knows
            captureThread = Thread({ silentLoop() }, "jieyan-voice-nomic").apply { start() }
        }
        val rep = Report(aecOn, nsOn, device, mic)
        report = rep
        JieyanLog.i("VOICE start mic=$mic aec=$aecOn ns=$nsOn device=$device session=$sessionId mode=${audio.mode}")
        return rep
    }

    fun stop() {
        if (!running) return
        running = false
        synchronized(lock) { lock.notifyAll() }
        captureThread?.join(500)
        playThread?.join(500)
        captureThread = null
        playThread = null
        runCatching { record?.stop() }
        record?.release()
        record = null
        aec?.release()
        aec = null
        ns?.release()
        ns = null
        runCatching { track?.stop() }
        track?.release()
        track = null
        synchronized(lock) { queue.clear(); primed = false }
        runCatching { audio.clearCommunicationDevice() }
        audio.mode = previousMode
        val s = stats
        JieyanLog.i("VOICE stop in=${s[0]} out=${s[1]} dropped=${s[2]} underruns=${s[3]}")
    }

    /** A packet from the expert (network thread). */
    fun onRemotePacket(p: ByteArray) {
        if (!running || p.size < 3) return
        stats[0]++
        remoteMuted = (p[2].toInt() and 1) != 0
        val n = (p.size - 3) / 2
        if (n <= 0) return
        val pcm = ShortArray(n)
        for (i in 0 until n) pcm[i] = ((p[3 + 2 * i].toInt() and 0xFF) or (p[4 + 2 * i].toInt() shl 8)).toShort()
        remoteLevel = level(pcm)
        lastRemoteAtMs = SystemClock.elapsedRealtime()
        enqueue(pcm)
    }

    /** The 0.3 s connect (rising) / hang-up (falling) tone, mixed into the playback queue. */
    fun chime(rising: Boolean) {
        if (!running) return
        val total = RATE * 3 / 10
        val all = ShortArray(total)
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            val f = if ((i < total / 2) == rising) 660.0 else 880.0
            val env = minOf(1.0, i / 160.0, (total - i) / 480.0)
            all[i] = (sin(2 * PI * f * t) * 6000 * env).toInt().toShort()
        }
        for (k in 0 until total / FRAME) enqueue(all.copyOfRange(k * FRAME, (k + 1) * FRAME), force = true)
    }

    private fun enqueue(pcm: ShortArray, force: Boolean = false) {
        synchronized(lock) {
            queue.addLast(pcm)
            while (!force && queue.size > MAX_PACKETS) {
                queue.removeFirst()
                stats[2]++
            }
            lock.notifyAll()
        }
    }

    private fun playLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val silence = ShortArray(FRAME)
        while (running) {
            val next: ShortArray? =
                synchronized(lock) {
                    if (!primed && queue.size >= PRIME_PACKETS) primed = true
                    if (primed && queue.isNotEmpty()) queue.removeFirst() else null
                }
            if (next != null) {
                t.write(next, 0, next.size)
            } else {
                // underrun: play 20 ms of silence (keeps the track and the canceller's reference running) and re-prime
                synchronized(lock) { if (primed) { primed = false; stats[3]++ } }
                t.write(silence, 0, silence.size)
            }
        }
    }

    private fun captureLoop(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(FRAME)
        var seq = 0
        var lastMutedSent = 0L
        var self = 0f
        var lastLevelsAt = 0L
        while (running) {
            var got = 0
            while (got < FRAME && running) {
                val n = r.read(buf, got, FRAME - got)
                if (n <= 0) break
                got += n
            }
            if (!running) break
            val now = SystemClock.elapsedRealtime()
            if (now < toneUntilMs) {
                for (i in 0 until FRAME) {
                    buf[i] = (sin(tonePhase) * 8000).toInt().toShort()
                    tonePhase += 2 * PI * 440 / RATE
                }
            }
            self = smooth(self, if (muted) 0f else level(buf))
            if (muted) {
                if (now - lastMutedSent >= MUTED_TICK_MS) {
                    lastMutedSent = now
                    listener.onPacket(header(seq++, true, 0))
                    stats[1]++
                }
            } else {
                val p = header(seq++, false, FRAME)
                for (i in 0 until FRAME) {
                    val v = buf[i].toInt()
                    p[3 + 2 * i] = v.toByte()
                    p[4 + 2 * i] = (v shr 8).toByte()
                }
                listener.onPacket(p)
                stats[1]++
            }
            if (now - lastLevelsAt >= LEVELS_MS) {
                lastLevelsAt = now
                listener.onLevels(self, remoteNow(now))
            }
        }
    }

    private fun silentLoop() {
        var seq = 0
        while (running) {
            listener.onPacket(header(seq++, true, 0))
            listener.onLevels(0f, remoteNow(SystemClock.elapsedRealtime()))
            SystemClock.sleep(MUTED_TICK_MS)
        }
    }

    private fun remoteNow(now: Long): Float = if (now - lastRemoteAtMs > 120) 0f else remoteLevel

    private fun header(seq: Int, muted: Boolean, samples: Int): ByteArray {
        val p = ByteArray(3 + samples * 2)
        p[0] = (seq shr 8).toByte()
        p[1] = seq.toByte()
        p[2] = if (muted) 1 else 0
        return p
    }

    companion object {
        const val RATE = 16_000

        /** 20 ms at 16 kHz. */
        const val FRAME = 320
        private const val PRIME_PACKETS = 3
        private const val MAX_PACKETS = 10
        private const val MUTED_TICK_MS = 200L
        private const val LEVELS_MS = 50L

        /** RMS of a packet mapped to 0..1 (about -50 dBFS .. -10 dBFS). */
        fun level(pcm: ShortArray): Float {
            var sum = 0.0
            for (s in pcm) sum += s.toDouble() * s
            val rms = sqrt(sum / pcm.size.coerceAtLeast(1)) / 32768.0
            val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
            return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        }

        private fun smooth(prev: Float, next: Float): Float = if (next > prev) next else prev * 0.8f + next * 0.2f

        private fun typeName(t: Int): String =
            when (t) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
                else -> "type$t"
            }
    }
}
