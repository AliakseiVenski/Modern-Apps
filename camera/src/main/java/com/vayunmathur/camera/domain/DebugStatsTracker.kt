package com.vayunmathur.camera.domain

/** What the sensor actually did over the last window of frames. */
data class DebugStats(
    val fps: Float,
    val exposureNanos: Long,
    val iso: Int,
    val focalMm: Float,
)

/**
 * Turns per-frame capture results into [DebugStats]: fps is measured from sensor timestamps over a
 * window, the rest comes from the frame that closes it. Pure; feed it from any capture callback.
 */
class DebugStatsTracker(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {
    private var windowStartNanos = 0L
    private var frames = 0

    /** Feeds one frame; returns stats when a window just closed, else null. */
    fun onFrame(timestampNanos: Long, exposureNanos: Long, iso: Int, focalMm: Float): DebugStats? {
        if (frames == 0) windowStartNanos = timestampNanos
        frames++
        val span = timestampNanos - windowStartNanos
        if (span < windowNanos) return null
        val stats = DebugStats(
            fps = (frames - 1) * 1e9f / span,
            exposureNanos = exposureNanos,
            iso = iso,
            focalMm = focalMm,
        )
        frames = 0
        return stats
    }

    fun reset() {
        frames = 0
    }

    companion object {
        const val DEFAULT_WINDOW_NANOS = 400_000_000L
    }
}
