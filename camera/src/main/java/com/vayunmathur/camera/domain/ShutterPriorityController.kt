package com.vayunmathur.camera.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Shutter time and ISO to request together. */
data class ExposureSetting(val timeNanos: Long, val iso: Int)

/**
 * Shutter-priority auto exposure for the video shutter lock. The shutter stays at one frame
 * ([frameNanos]) and ISO absorbs brightness changes; only when ISO bottoms out at [isoMin] does
 * the shutter shorten. Fed the mean luma (0..255) of metered frames at a steady rate, it ramps total
 * exposure (time x ISO) toward [TARGET_LUMA] continuously: luma is low-pass filtered, the correction
 * is a first-order approach, and each step is rate-limited, so brightness glides instead of stepping.
 */
class ShutterPriorityController(
    private val frameNanos: Long,
    private val isoMin: Int,
    private val isoMax: Int,
    start: ExposureSetting,
) {
    private var exposure = clamp(start.timeNanos.toDouble() * start.iso)
    private var smoothedLuma = -1.0

    var current: ExposureSetting = split(exposure)
        private set

    /** Feeds one metered frame [dtSeconds] after the previous; returns the new setting when it moved, else null. */
    fun update(meanLuma: Double, dtSeconds: Double): ExposureSetting? {
        val luma = meanLuma.coerceAtLeast(MIN_LUMA)
        smoothedLuma = if (smoothedLuma < 0) luma else smoothedLuma + (luma - smoothedLuma) * (1 - exp(-dtSeconds / LUMA_TAU))
        val errorStops = GAMMA * log2(TARGET_LUMA / smoothedLuma)
        if (abs(errorStops) < DEADBAND_STOPS) return null
        val maxStep = MAX_STOPS_PER_SECOND * dtSeconds
        val step = (errorStops * (1 - exp(-dtSeconds / EXPOSURE_TAU))).coerceIn(-maxStep, maxStep)
        exposure = clamp(exposure * 2.0.pow(step))
        val next = split(exposure)
        if (next == current) return null
        current = next
        return next
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun clamp(x: Double) = x.coerceIn(MIN_TIME_NANOS * isoMin, frameNanos.toDouble() * isoMax)

    private fun split(x: Double): ExposureSetting {
        val time = minOf(frameNanos.toDouble(), x / isoMin)
        return ExposureSetting(time.toLong(), (x / time).roundToInt().coerceIn(isoMin, isoMax))
    }

    companion object {
        const val TARGET_LUMA = 105.0
        private const val MIN_LUMA = 1.0

        /** Luma follows exposure roughly as exposure^(1/2.2), so a luma error of n stops needs 2.2n stops of exposure. */
        private const val GAMMA = 2.2
        private const val LUMA_TAU = 0.15
        private const val EXPOSURE_TAU = 0.5
        private const val MAX_STOPS_PER_SECOND = 2.0
        private const val DEADBAND_STOPS = 0.08
        private const val MIN_TIME_NANOS = 50_000.0
    }
}
