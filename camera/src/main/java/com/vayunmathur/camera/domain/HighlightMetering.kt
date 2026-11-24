package com.vayunmathur.camera.domain

/**
 * Brightness the shutter-priority controller steers by: the frame mean, read brighter only when a real
 * share of the frame is clipped, so the controller backs off until a blown light source recovers. A
 * bright window or sky that is merely light (not at the ceiling) is left to the mean, which is what the
 * camera's own AE does, so switching from Auto to an ISO limit does not darken such scenes.
 */
object HighlightMetering {
    /** Luma at or above this counts as clipped (8-bit processed output). */
    const val CLIP_LEVEL = 250f
    const val CLIP_START = 235f

    /** Share of clipped pixels tolerated before the reading is pushed up. */
    const val CLIP_ALLOWED = 0.03

    /** Reading increase per unit of clipped share above [CLIP_ALLOWED]. */
    const val CLIP_GAIN = 8.0

    /**
     * The reading never rises by more than this factor. A hard lift makes the control swing: a window
     * near the clip level flips between 0 % and 30 % clipped on a small exposure change.
     */
    const val MAX_FACTOR = 1.8

    /** Metered luma (0..255 scale) for a downsampled luma [grid]; 0 for an empty grid. */
    fun effectiveLuma(grid: FloatArray): Double {
        if (grid.isEmpty()) return 0.0
        var sum = 0.0
        var clipped = 0.0
        for (value in grid) {
            sum += value
            // A soft edge, not a threshold: a pixel counts as clipped gradually from CLIP_START to CLIP_LEVEL, so
            // a bright window drifting around the clip level does not flip the reading (that made it oscillate).
            clipped += ((value - CLIP_START) / (CLIP_LEVEL - CLIP_START)).coerceIn(0f, 1f)
        }
        val excess = (clipped / grid.size - CLIP_ALLOWED).coerceAtLeast(0.0)
        return sum / grid.size * (1.0 + CLIP_GAIN * excess).coerceAtMost(MAX_FACTOR)
    }
}
