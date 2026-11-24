package com.vayunmathur.camera.platform

import android.os.SystemClock
import com.vayunmathur.camera.domain.HighlightMetering
import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.camera.domain.ShutterPriorityController
import com.vayunmathur.camera.platform.camera2.Camera2Controls
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.RawStillMetadata
import com.vayunmathur.camera.platform.camera2.aeLocked
import com.vayunmathur.camera.platform.camera2.setIsoCap
import com.vayunmathur.camera.platform.camera2.setShutterPriority

/** The cap the viewfinder keeps while a video mode is showing: fps sets the shutter, [isoLimit] the ceiling. */
internal data class IsoCapSetup(val fps: Int, val isoMin: Int, val isoLimit: Int, val uncapped: Boolean = false)

/**
 * The shutter lock for the live preview. The camera's AE runs only until it has found the brightness; then
 * [ShutterPriorityController] holds the exposure for good (AE off, shutter one frame or shorter, ISO up to
 * the limit).
 *
 * Each analysis frame is judged against the exposure that frame was really taken with (its capture result,
 * matched by sensor timestamp), never against the last request: the camera applies a request a few frames
 * late, and judging the latest request is what made earlier versions overshoot, hunt and dip. A lens change
 * needs nothing special: the first frames of the new lens show its brightness at the known exposure, and
 * the next request corrects it.
 */
internal class PreviewIsoCap {
    private var setup: IsoCapSetup? = null
    private var controller: ShutterPriorityController? = null
    private var lastMs = 0L
    private var lastAutoLumaMs = 0L

    /** True while this controller sets the exposure; false while the camera's AE does (start-up). */
    private var manual = false
    private var startedAtMs = 0L
    private var nativeSinceMs = 0L
    private var nativeFailed = false
    private var lastAeProduct = 0.0
    private var aeStableSinceMs = 0L
    private var aeLuma = -1.0
    private var aeLumaAtMs = 0L

    /** What a recent frame was really taken with, by sensor timestamp, from its capture result. */
    private class FrameInfo(val exposure: Double, val lens: String?)

    private val frames = object : LinkedHashMap<Long, FrameInfo>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FrameInfo>?) = size > FRAME_HISTORY
    }

    /** Zoom ratios each physical lens has been seen serving (min to max), to predict a switch. */
    private val lensZoom = HashMap<String, Pair<Float, Float>>()

    /** Relative sensitivity of each physical lens, in stops, measured across switches. */
    private val lensStops = HashMap<String, Double>()

    /** The last judged frame: lens, luma, exposure. A frame of another lens right after it measures the step. */
    private var lastLens: String? = null
    private var lastLuma = 0.0
    private var lastExposure = 0.0
    private var expectedLens: String? = null
    private var expectUntilMs = 0L

    /** Applies a new cap (null = none); restarts the controller when it changed. */
    @Synchronized
    fun configure(newSetup: IsoCapSetup?) {
        if (newSetup == setup) return
        setup = newSetup
        controller = null
        manual = false
        startedAtMs = 0L
        nativeSinceMs = 0L
        nativeFailed = false
        lastMs = 0L
        lastAeProduct = 0.0
        aeLuma = -1.0
        aeLumaAtMs = 0L
        status.value = newSetup?.let { "ISO limit ${it.isoLimit}: waiting for the camera" }
    }

    /** One line for the debug overlay. */
    val status = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Capture result of a preview frame (session executor thread). */
    @Synchronized
    fun onFrameResult(raw: RawStillMetadata) {
        val lens = raw.physicalCameraId
        val zoom = raw.zoomRatio
        if (lens != null && zoom != null) {
            val seen = lensZoom[lens]
            lensZoom[lens] = if (seen == null) zoom to zoom else minOf(seen.first, zoom) to maxOf(seen.second, zoom)
        }
        val ts = raw.sensorTimestampNs ?: return
        val time = raw.exposureTimeNs ?: return
        val iso = raw.iso ?: return
        if (time > 0L && iso > 0) frames[ts] = FrameInfo(time.toDouble() * iso, lens)
    }

    /** One analysis frame of the preview. */
    @Synchronized
    fun onYPlane(plane: PlaneView, timestampNs: Long, session: Camera2Session?) {
        val cap = setup ?: return publishAutoLuma(plane)
        session ?: return
        val now = SystemClock.elapsedRealtime()
        val frameNanos = NANOS_PER_SECOND / cap.fps
        if (cap.uncapped && !nativeFailed && 1 in session.characteristics.aePriorityModes) {
            nativeShutterPriority(frameNanos, session, now)
            return
        }
        if (session.controlState.shutterPriorityNs != null) session.setShutterPriority(null)
        if (!manual) {
            watchCameraAe(cap, frameNanos, session, plane, now)
            return
        }
        val active = controller ?: return
        if (session.aeLocked) {
            status.value = "ISO limit ${cap.isoLimit}: AE locked"
            return
        }
        // Only a frame whose real exposure is known can be judged; others (result not in yet) are skipped.
        val info = frames[timestampNs] ?: return
        val exposure = info.exposure
        val metered = meter(plane, session) ?: return
        learnLensStep(info.lens, metered, exposure)
        // A predicted lens switch was sent with its exposure: frames still from the old lens would undo it.
        val waiting = expectedLens
        if (waiting != null) {
            if (info.lens == waiting || now > expectUntilMs) expectedLens = null else return
        }
        val since = if (lastMs == 0L) MIN_DT_MS else (now - lastMs).coerceIn(MIN_DT_MS, MAX_GAP_MS)
        lastMs = now
        // Exposure compensation: no AE to nudge, so the target moves. Luma ~ exposure^(1/2.2).
        val luma = metered / Math.pow(2.0, session.controlState.exposureCompEv / LUMA_GAMMA)
        active.steer(exposure, luma, since / 1000.0)?.let { session.setIsoCap(it.timeNanos, it.iso) }
        val snapshot = session.characteristics
        val (sensorIso, boost) = com.vayunmathur.camera.domain.IsoSplit.split(
            active.current.iso,
            snapshot.sensitivityRange?.upper ?: active.current.iso,
            snapshot.postRawBoostRange?.upper ?: com.vayunmathur.camera.domain.IsoSplit.UNITY_BOOST,
        )
        status.value = "ISO limit ${cap.isoLimit}: held ISO ${active.current.iso} = sensor $sensorIso x boost $boost%, luma ${luma.toInt()}"
    }

    /**
     * Start-up: the camera's AE finds the brightness. Once it has held still (or after a moment) the held
     * exposure takes over for good, starting from AE's exposure and keeping the brightness AE made.
     */
    private fun watchCameraAe(cap: IsoCapSetup, frameNanos: Long, session: Camera2Session, plane: PlaneView, now: Long) {
        val (time, iso) = session.latestAeSnapshot()
        if (time == null || iso == null || time <= 0L || iso <= 0) {
            status.value = "ISO limit ${cap.isoLimit}: waiting for the camera"
            return
        }
        val product = time.toDouble() * iso
        if (lastAeProduct <= 0.0 || kotlin.math.abs(product / lastAeProduct - 1.0) > AE_STABLE_TOLERANCE) {
            aeStableSinceMs = now
            lastAeProduct = product
        }
        meter(plane, session)?.let { metered ->
            val dt = if (aeLumaAtMs == 0L) 0.0 else (now - aeLumaAtMs) / 1000.0
            aeLuma = if (aeLuma < 0.0) metered else aeLuma + (metered - aeLuma) * (1 - kotlin.math.exp(-dt / AE_LUMA_TAU))
            aeLumaAtMs = now
        }
        status.value = "ISO limit ${cap.isoLimit}: camera AE 1/${NANOS_PER_SECOND / time} ISO $iso, starting"
        if (startedAtMs == 0L) startedAtMs = now
        val settled = now - aeStableSinceMs >= AE_STABLE_MS || now - startedAtMs >= START_TIMEOUT_MS
        if (!settled || session.aeLocked || aeLuma < 0.0) return
        // AE already applied the exposure compensation to this brightness; the held loop applies it itself.
        val target = (aeLuma / Math.pow(2.0, session.controlState.exposureCompEv / LUMA_GAMMA))
            .coerceIn(ANCHOR_MIN_LUMA, ANCHOR_MAX_LUMA)
        val start = com.vayunmathur.camera.domain.ExposureSetting(frameNanos, (product / frameNanos).toInt().coerceAtLeast(1))
        controller = ShutterPriorityController(frameNanos, cap.isoMin, cap.isoLimit, start, target).also {
            session.setIsoCap(it.current.timeNanos, it.current.iso)
        }
        manual = true
        lastMs = 0L
    }

    /**
     * With no ISO ceiling, where the phone has Android 16 shutter-priority AE, the camera does it all: AE on,
     * exposure time pinned to one frame. If the camera does not honour it the held exposure is used instead.
     */
    private fun nativeShutterPriority(frameNanos: Long, session: Camera2Session, now: Long) {
        if (nativeSinceMs == 0L) {
            nativeSinceMs = now
            session.setShutterPriority(frameNanos)
            status.value = "Shutter lock: starting"
            return
        }
        val (time, iso) = session.latestAeSnapshot()
        if (time == null || iso == null) return
        status.value = "Shutter lock (camera AE): 1/${NANOS_PER_SECOND / time.coerceAtLeast(1L)} ISO $iso"
        if (time > frameNanos * NATIVE_FAIL_FRACTION && now - nativeSinceMs > NATIVE_FAIL_AFTER_MS) {
            nativeFailed = true
            nativeSinceMs = 0L
            session.setShutterPriority(null)
        }
    }

    /**
     * Same scene, adjacent frames, two lenses: the luma and exposure on each side give the lenses' relative
     * sensitivity (luma ~ (exposure x sensitivity)^(1/2)). Averaged per lens over switches.
     */
    private fun learnLensStep(lens: String?, luma: Double, exposure: Double) {
        val previous = lastLens
        if (lens != null && previous != null && lens != previous &&
            luma in USABLE_LUMA && lastLuma in USABLE_LUMA && exposure > 0.0 && lastExposure > 0.0
        ) {
            val stepStops = LENS_GAMMA * log2(luma / lastLuma) + log2(lastExposure / exposure)
            val base = lensStops.getOrPut(previous) { 0.0 }
            val measured = base + stepStops
            lensStops[lens] = lensStops[lens]?.let { (it + measured) / 2 } ?: measured
        }
        if (lens != null) {
            lastLens = lens
            lastLuma = luma
            lastExposure = exposure
        }
    }

    /**
     * A zoom request is about to go out. When it moves to another physical lens whose sensitivity is known,
     * returns the exposure to send in the same request, so the first frame of the new lens is already right.
     */
    @Synchronized
    fun exposureForZoom(ratio: Float): com.vayunmathur.camera.domain.ExposureSetting? {
        val active = controller ?: return null
        if (!manual) return null
        val current = lastLens ?: return null
        val target = lensZoom.entries.filter { ratio >= it.value.first - ZOOM_MARGIN && ratio <= it.value.second + ZOOM_MARGIN }
            .map { it.key }.let { if (current in it) current else it.singleOrNull() } ?: return null
        if (target == current) return null
        val from = lensStops[current] ?: return null
        val to = lensStops[target] ?: return null
        expectedLens = target
        expectUntilMs = SystemClock.elapsedRealtime() + EXPECT_TIMEOUT_MS
        return active.scaleExposure(Math.pow(2.0, from - to))
    }

    private fun log2(x: Double) = kotlin.math.ln(x) / kotlin.math.ln(2.0)

    /** Without a cap the overlay still shows the frame's luma, to compare Auto with the lock. */
    private fun publishAutoLuma(plane: PlaneView) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoLumaMs < AUTO_LUMA_INTERVAL_MS) return
        lastAutoLumaMs = now
        val luma = LumaPlaneMath.grid(plane)?.first?.let { HighlightMetering.effectiveLuma(it) } ?: return
        status.value = "Auto ISO: luma ${luma.toInt()}"
    }

    /** A tapped point meters its own window (as the HAL's AE does), else the whole frame. */
    private fun meter(plane: PlaneView, session: Camera2Session): Double? =
        tappedLuma(plane, session) ?: LumaPlaneMath.grid(plane)?.first?.let { HighlightMetering.effectiveLuma(it) }

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
        const val MIN_DT_MS = 16L
        const val MAX_GAP_MS = 200L
        const val FRAME_HISTORY = 32
        const val LENS_GAMMA = 2.0
        val USABLE_LUMA = 12.0..235.0
        const val ZOOM_MARGIN = 0.02f
        const val EXPECT_TIMEOUT_MS = 400L
        const val AUTO_LUMA_INTERVAL_MS = 300L
        const val ANCHOR_MIN_LUMA = 60.0
        const val ANCHOR_MAX_LUMA = 160.0
        const val AE_STABLE_MS = 250L
        const val START_TIMEOUT_MS = 1500L
        const val AE_STABLE_TOLERANCE = 0.05
        const val AE_LUMA_TAU = 0.2
        const val NATIVE_FAIL_FRACTION = 1.15
        const val NATIVE_FAIL_AFTER_MS = 600L
    }
}
