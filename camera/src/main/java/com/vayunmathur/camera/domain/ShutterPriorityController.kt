package com.vayunmathur.camera.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shutter time and ISO to request together. */
data class ExposureSetting(val timeNanos: Long, val iso: Int)

/**
 * Shutter-priority auto exposure for the video shutter lock. The shutter stays at one frame
 * ([frameNanos]) and ISO absorbs brightness changes; only when ISO bottoms out at [isoMin] does
 * the shutter shorten. Fed the mean luma (0..255) of metered frames, it steers total exposure
 * (time x ISO) toward [TARGET_LUMA] in damped steps, so brightness follows the scene without pumping.
 */
class ShutterPriorityController(
    private val frameNanos: Long,
    private val isoMin: Int,
    private val isoMax: Int,
    start: ExposureSetting,
) {
    private var exposure = clamp(start.timeNanos.toDouble() * start.iso)

    var current: ExposureSetting = split(exposure)
        private set

    /** Returns the new setting when [meanLuma] calls for a visible change, else null. */
    fun update(meanLuma: Double): ExposureSetting? {
        val ratio = TARGET_LUMA / meanLuma.coerceAtLeast(MIN_LUMA)
        if (abs(ratio - 1.0) < DEADBAND) return null
        exposure = clamp(exposure * ratio.pow(STEP_EXPONENT))
        val next = split(exposure)
        if (next == current) return null
        current = next
        return next
    }

    private fun clamp(x: Double) = x.coerceIn(MIN_TIME_NANOS * isoMin, frameNanos.toDouble() * isoMax)

    private fun split(x: Double): ExposureSetting {
        val time = minOf(frameNanos.toDouble(), x / isoMin)
        return ExposureSetting(time.toLong(), (x / time).roundToInt().coerceIn(isoMin, isoMax))
    }

    companion object {
        const val TARGET_LUMA = 105.0
        private const val MIN_LUMA = 1.0
        private const val DEADBAND = 0.06

        /** About 1/gamma-corrected, halved: luma ~ exposure^(1/2.2), so 1.1 closes half the error per step. */
        private const val STEP_EXPONENT = 1.1
        private const val MIN_TIME_NANOS = 50_000.0
    }
}
