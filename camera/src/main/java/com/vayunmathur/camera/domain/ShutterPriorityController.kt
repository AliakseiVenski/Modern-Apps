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
    /** Luma to hold; the viewfinder anchors it to the look the camera's own AE had when the cap began. */
    private val targetLuma: Double = TARGET_LUMA,
) {
    private var exposure = clamp(start.timeNanos.toDouble() * start.iso)
    private var smoothedLuma = -1.0

    var current: ExposureSetting = split(exposure)
        private set

    /** Feeds one metered frame [dtSeconds] after the previous; returns the new setting when it moved, else null. */
    fun update(meanLuma: Double, dtSeconds: Double): ExposureSetting? {
        val luma = meanLuma.coerceAtLeast(MIN_LUMA)
        val first = smoothedLuma < 0
        smoothedLuma = when {
            first -> luma
            // A scene cut (dark to bright or back): trust the new reading now instead of waiting out the filter.
            luma > smoothedLuma * CUT_RATIO || luma < smoothedLuma / CUT_RATIO -> luma
            else -> smoothedLuma + (luma - smoothedLuma) * (1 - exp(-dtSeconds / LUMA_TAU))
        }
        val errorStops = GAMMA * log2(targetLuma / smoothedLuma)
        if (abs(errorStops) < DEADBAND_STOPS) return null
        // The first reading corrects at once (a camera just opened has no exposure to glide from);
        // after that the rate grows with the error, so a big change is quick and a small one is smooth.
        val maxStep = if (first) FIRST_STEP_MAX_STOPS else stopsPerSecond(abs(errorStops)) * dtSeconds
        val response = if (first) 1.0 else minOf(1 - exp(-dtSeconds / EXPOSURE_TAU), MAX_RESPONSE)
        val step = (errorStops * response).coerceIn(-maxStep, maxStep)
        exposure = clamp(exposure * 2.0.pow(step))
        val next = split(exposure)
        if (next == current) return null
        current = next
        return next
    }

    /**
     * Feedback against the exposure the measured frame was really taken with ([frameExposure], time x ISO
     * from its capture result), not against the last request. The exposure that would put this frame on
     * target is fixed whatever requests are still in flight, so steering toward it cannot overshoot because
     * of the camera's few frames of delay. The request glides there ([STEER_TAU]); a jump of more than
     * [CUT_STOPS] (a lens change, a scene cut) is taken in one step.
     */
    fun steer(frameExposure: Double, meanLuma: Double, dtSeconds: Double): ExposureSetting? {
        val luma = meanLuma.coerceAtLeast(MIN_LUMA)
        val errorStops = (STEER_GAMMA * log2(targetLuma / luma)).coerceIn(-MAX_ERROR_STOPS, MAX_ERROR_STOPS)
        val desired = clamp(frameExposure * 2.0.pow(errorStops))
        val gap = log2(desired / exposure)
        if (abs(gap) < STEER_DEADBAND_STOPS) return null
        val fraction = if (abs(gap) >= CUT_STOPS) 1.0 else 1 - exp(-dtSeconds / STEER_TAU)
        exposure = clamp(exposure * 2.0.pow(gap * fraction))
        val next = split(exposure)
        if (next == current) return null
        current = next
        return next
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun stopsPerSecond(errorStops: Double): Double =
        BASE_STOPS_PER_SECOND + (MAX_STOPS_PER_SECOND - BASE_STOPS_PER_SECOND) * minOf(errorStops / FAST_ERROR_STOPS, 1.0)

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
        private const val LUMA_TAU = 0.1
        private const val EXPOSURE_TAU = 0.4
        private const val BASE_STOPS_PER_SECOND = 2.0
        private const val MAX_STOPS_PER_SECOND = 6.0

        /**
         * Share of the remaining error corrected per step. Small, because steps come every frame or two and the
         * camera applies each a few frames late: many small steps glide, a few large ones show as jumps and hunt.
         */
        private const val MAX_RESPONSE = 0.1
        private const val FAST_ERROR_STOPS = 4.0
        private const val FIRST_STEP_MAX_STOPS = 8.0
        private const val CUT_RATIO = 2.2

        /** Tone curve slope used by [steer]: a stop of exposure moves luma by about half a stop. */
        private const val STEER_GAMMA = 2.0
        private const val MAX_ERROR_STOPS = 4.0
        private const val STEER_TAU = 0.12
        private const val STEER_DEADBAND_STOPS = 0.04
        private const val CUT_STOPS = 1.0
        private const val DEADBAND_STOPS = 0.08
        private const val MIN_TIME_NANOS = 50_000.0
    }
}
