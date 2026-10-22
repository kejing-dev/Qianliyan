package io.embodit.jieyan.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.jieyan.domain.HeadPose
import io.embodit.jieyan.domain.Quat4
import io.embodit.jieyan.domain.Vec3
import io.embodit.jieyan.platform.JieyanLog

/**
 * HMD pose source (`HMDTrackingProvider`), read once per frame from the ECS system; the last valid
 * pose is kept so that a missing sample does not make the lazy-follow panels jump.
 *
 * The provider's support state is `NOT_IN_FULL_SPACE` while the Stage is still opening and turns to
 * `SUPPORTED` by itself (Spatial Ruler, SDK 6.1.9 on the emulator); nothing here depends on it.
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var lastValidMs = 0L

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        val support = runCatching { p.supportState }.getOrNull()
        JieyanLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=$support")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
    }

    fun supportState(): DataProvider.SupportState? = provider?.let { p -> runCatching { p.supportState }.getOrNull() }

    /** Current head pose, or the last one that was valid, or null before the first sample. */
    fun pose(): HeadPose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        val sample = HeadPose(Vec3(position.x, position.y, position.z), Quat4(rotation.x, rotation.y, rotation.z, rotation.w))
        if (!sample.position.isFinite()) return last
        last = sample
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }
}
