package com.vayunmathur.camera.platform

import android.os.SystemClock
import com.vayunmathur.camera.domain.ExposureSetting
import com.vayunmathur.camera.domain.HighlightMetering
import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.camera.domain.ShutterPriorityController
import com.vayunmathur.camera.platform.camera2.Camera2Controls
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.aeLocked
import com.vayunmathur.camera.platform.camera2.setIsoCap

/** The cap the viewfinder keeps while a video mode is showing: fps sets the shutter, [isoLimit] the ceiling. */
internal data class IsoCapSetup(val fps: Int, val isoMin: Int, val isoLimit: Int)

/**
 * Keeps the live preview under the user's ISO cap, as the recording engine does for the take: AE off
 * with one frame of shutter, ISO moved smoothly by [ShutterPriorityController] from the mean luma of
 * the analysis stream, never above the cap.
 */
internal class PreviewIsoCap {
    private var setup: IsoCapSetup? = null
    private var controller: ShutterPriorityController? = null
    private var lastMs = 0L

    /** Applies a new cap (null = none); restarts the controller when it changed. */
    @Synchronized
    fun configure(newSetup: IsoCapSetup?) {
        if (newSetup == setup) return
        setup = newSetup
        controller = null
        lastMs = 0L
        status.value = newSetup?.let { "ISO cap ${it.isoLimit}: waiting for analysis frames" }
    }

    /** One line for the debug overlay: null when no cap is set, else whether the viewfinder is metering. */
    val status = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** One analysis frame of the preview. */
    @Synchronized
    fun onYPlane(plane: PlaneView, session: Camera2Session?) {
        val cap = setup ?: return
        session ?: return
        if (session.aeLocked) {
            status.value = "ISO cap ${cap.isoLimit}: AE locked"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val since = if (lastMs == 0L) INTERVAL_MS else now - lastMs
        if (since < INTERVAL_MS) return
        // A tapped point meters its own window (as the HAL's AE does), else the whole frame.
        val luma = tappedLuma(plane, session)
            ?: LumaPlaneMath.grid(plane)?.first?.let { HighlightMetering.effectiveLuma(it) } ?: return
        lastMs = now
        val frameNanos = NANOS_PER_SECOND / cap.fps
        val active = controller ?: ShutterPriorityController(
            frameNanos, cap.isoMin, cap.isoLimit,
            ExposureSetting(frameNanos, SEED_ISO.coerceIn(cap.isoMin, cap.isoLimit)),
        ).also {
            controller = it
            session.setIsoCap(it.current.timeNanos, it.current.iso)
        }
        active.update(luma, since.coerceAtMost(MAX_GAP_MS) / 1000.0)?.let {
            session.setIsoCap(it.timeNanos, it.iso)
        }
        val snapshot = session.characteristics
        val (sensorIso, boost) = com.vayunmathur.camera.domain.IsoSplit.split(
            active.current.iso,
            snapshot.sensitivityRange?.upper ?: active.current.iso,
            snapshot.postRawBoostRange?.upper ?: com.vayunmathur.camera.domain.IsoSplit.UNITY_BOOST,
        )
        status.value = "ISO cap ${cap.isoLimit}: ISO ${active.current.iso} = sensor $sensorIso x boost $boost%, luma ${luma.toInt()}"
    }

    /** Mean luma of the tapped AE window on the analysis plane, or null when no point is metered. */
    private fun tappedLuma(plane: PlaneView, session: Camera2Session): Double? {
        val state = session.controlState
        val region = state.aeRegion?.rect ?: return null
        val crop = Camera2Controls.currentCropRegion(state, session.characteristics)
        if (crop.width <= 0 || crop.height <= 0 || plane.width <= 0 || plane.height <= 0) return null
        fun scaled(value: Int, origin: Int, span: Int, size: Int) =
            (((value - origin).toFloat() / span) * size).toInt().coerceIn(0, size - 1)
        val x0 = scaled(region.left, crop.left, crop.width, plane.width)
        val x1 = scaled(region.right, crop.left, crop.width, plane.width)
        val y0 = scaled(region.top, crop.top, crop.height, plane.height)
        val y1 = scaled(region.bottom, crop.top, crop.height, plane.height)
        var sum = 0L
        var count = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                sum += plane.buffer.get(plane.offsetOf(x, y)).toInt() and 0xFF
                count++
                x += WINDOW_STEP
            }
            y += WINDOW_STEP
        }
        return if (count == 0) null else sum.toDouble() / count
    }

    private companion object {
        const val WINDOW_STEP = 4
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val INTERVAL_MS = 50L
        const val MAX_GAP_MS = 200L
        const val SEED_ISO = 400
    }
}
