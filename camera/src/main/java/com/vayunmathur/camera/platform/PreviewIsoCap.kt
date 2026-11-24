package com.vayunmathur.camera.platform

import android.os.SystemClock
import com.vayunmathur.camera.domain.ExposureSetting
import kotlin.math.roundToInt
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
    private var lastAutoLumaMs = 0L

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
        val cap = setup ?: return publishAutoLuma(plane)
        session ?: return
        if (session.aeLocked) {
            status.value = "ISO cap ${cap.isoLimit}: AE locked"
            return
        }
        val now = SystemClock.elapsedRealtime()
        val since = if (lastMs == 0L) INTERVAL_MS else now - lastMs
        if (since < INTERVAL_MS) return
        // A tapped point meters its own window (as the HAL's AE does), else the whole frame.
        val metered = tappedLuma(plane, session)
            ?: LumaPlaneMath.grid(plane)?.first?.let { HighlightMetering.effectiveLuma(it) } ?: return
        lastMs = now
        // Exposure compensation: the exposure control has no AE to nudge, so it steers to a brighter
        // or darker target. Luma follows exposure^(1/2.2), so +1 EV lowers the reading by 2^(1/2.2).
        val luma = metered / Math.pow(2.0, session.controlState.exposureCompEv / LUMA_GAMMA)
        val frameNanos = NANOS_PER_SECOND / cap.fps
        val active = controller ?: newController(cap, frameNanos, session, metered).also {
            controller = it
            command(session, it.current)
        }
        active.update(luma, since.coerceAtMost(MAX_GAP_MS) / 1000.0)?.let { command(session, it) }
        val snapshot = session.characteristics
        val (sensorIso, boost) = com.vayunmathur.camera.domain.IsoSplit.split(
            active.current.iso,
            snapshot.sensitivityRange?.upper ?: active.current.iso,
            snapshot.postRawBoostRange?.upper ?: com.vayunmathur.camera.domain.IsoSplit.UNITY_BOOST,
        )
        status.value = "ISO cap ${cap.isoLimit}: ISO ${active.current.iso} = sensor $sensorIso x boost $boost%, luma ${luma.toInt()}"
    }

    /**
     * Starts the controller where the camera's own AE is, and holds the brightness that AE had made (within
     * sane bounds): limiting the ISO then keeps the picture as it was instead of re-judging the scene.
     */
    private fun newController(cap: IsoCapSetup, frameNanos: Long, session: Camera2Session, metered: Double): ShutterPriorityController {
        val aeKnown = session.latestAeSnapshot().let { (time, iso) -> time != null && iso != null && time > 0L && iso > 0 }
        val target = if (aeKnown) metered.coerceIn(ANCHOR_MIN_LUMA, ANCHOR_MAX_LUMA) else ShutterPriorityController.TARGET_LUMA
        return ShutterPriorityController(frameNanos, cap.isoMin, cap.isoLimit, seed(session, frameNanos), target)
    }

    private fun command(session: Camera2Session, setting: ExposureSetting) =
        session.setIsoCap(setting.timeNanos, setting.iso)

    /** Without a cap the overlay still shows the frame's luma, to compare Auto with a limit. */
    private fun publishAutoLuma(plane: PlaneView) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoLumaMs < AUTO_LUMA_INTERVAL_MS) return
        lastAutoLumaMs = now
        val luma = LumaPlaneMath.grid(plane)?.first?.let { HighlightMetering.effectiveLuma(it) } ?: return
        status.value = "Auto ISO: luma ${luma.toInt()}"
    }

    /**
     * Where the exposure starts: what the camera's own AE is using right now, so the viewfinder picks up
     * from the scene it already shows instead of climbing from a fixed ISO. A fixed seed only when no
     * frame result has arrived yet.
     */
    private fun seed(session: Camera2Session, frameNanos: Long): ExposureSetting {
        val (timeNs, iso) = session.latestAeSnapshot()
        if (timeNs == null || iso == null || timeNs <= 0L || iso <= 0) return ExposureSetting(frameNanos, SEED_ISO)
        // One frame of shutter with the equal total exposure; the controller clamps and splits it.
        return ExposureSetting(frameNanos, (timeNs.toDouble() * iso / frameNanos).roundToInt().coerceAtLeast(1))
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
        const val LUMA_GAMMA = 2.2
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val INTERVAL_MS = 33L
        const val AUTO_LUMA_INTERVAL_MS = 300L
        const val ANCHOR_MIN_LUMA = 85.0
        const val ANCHOR_MAX_LUMA = 125.0
        const val MAX_GAP_MS = 200L
        const val SEED_ISO = 400
    }
}
