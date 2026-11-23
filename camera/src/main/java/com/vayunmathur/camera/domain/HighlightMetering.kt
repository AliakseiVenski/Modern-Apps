package com.vayunmathur.camera.domain

import kotlin.math.max

/**
 * Brightness the shutter-priority controller steers by. A plain mean lets a dark surround drag the
 * exposure up until a bright light source clips (the HAL's own AE protects those highlights). This
 * keeps the mean, but never reads brighter than the highlights allow: the [HIGHLIGHT_PERCENTILE]
 * luma, scaled so that it sits at [HIGHLIGHT_CEILING] when the mean is on target, acts as a floor on
 * the metered value. A clipped light source then pulls the exposure down until it just stops clipping.
 */
object HighlightMetering {
    const val HIGHLIGHT_PERCENTILE = 0.98
    const val HIGHLIGHT_CEILING = 240.0
    private const val BINS = 256

    /** Metered luma (0..255 scale) for a downsampled luma [grid]; 0 for an empty grid. */
    fun effectiveLuma(grid: FloatArray): Double {
        if (grid.isEmpty()) return 0.0
        var sum = 0.0
        val histogram = IntArray(BINS)
        for (value in grid) {
            sum += value
            histogram[value.toInt().coerceIn(0, BINS - 1)]++
        }
        val mean = sum / grid.size
        val rank = (grid.size * HIGHLIGHT_PERCENTILE).toInt()
        var seen = 0
        var percentile = 0
        for (bin in 0 until BINS) {
            seen += histogram[bin]
            if (seen > rank) {
                percentile = bin
                break
            }
        }
        return max(mean, percentile * ShutterPriorityController.TARGET_LUMA / HIGHLIGHT_CEILING)
    }
}
