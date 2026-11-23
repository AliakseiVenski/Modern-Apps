package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.DebugStats
import com.vayunmathur.camera.domain.DebugStatsTracker
import kotlinx.coroutines.flow.MutableStateFlow

/** What the current recording asked for, shown next to the measured values in the debug overlay. */
data class VideoBindSummary(
    val resolution: String,
    val codec: String,
    val fpsTarget: Int,
    val bitrateMbps: Int,
    val stabilization: String,
)

/**
 * State behind the viewfinder debug overlay. Frames arrive from the preview session's capture results
 * and, while recording, from the recording engine's own session; both feed [onFrame]. It does nothing
 * while the overlay is off.
 */
internal class DebugOverlayState {
    val enabled = MutableStateFlow(false)
    val stats = MutableStateFlow<DebugStats?>(null)
    val bind = MutableStateFlow<VideoBindSummary?>(null)
    private val tracker = DebugStatsTracker()

    @Synchronized
    fun onFrame(timestampNs: Long?, exposureNs: Long?, iso: Int?, focalMm: Float?) {
        if (!enabled.value) {
            tracker.reset()
            return
        }
        val timestamp = timestampNs ?: return
        tracker.onFrame(timestamp, exposureNs ?: 0L, iso ?: 0, focalMm ?: 0f)?.let { stats.value = it }
    }

    /** Drops the measured values and the bind summary, e.g. when a recording ends. */
    @Synchronized
    fun reset() {
        tracker.reset()
        stats.value = null
        bind.value = null
    }
}
