package com.vayunmathur.camera.platform

import android.os.SystemClock
import com.vayunmathur.camera.domain.ExposureSetting
import com.vayunmathur.camera.domain.LumaPlaneMath
import com.vayunmathur.camera.domain.PlaneView
import com.vayunmathur.camera.domain.ShutterPriorityController
import com.vayunmathur.camera.platform.camera2.Camera2Session

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
    }

    /** One analysis frame of the preview. */
    @Synchronized
    fun onYPlane(plane: PlaneView, session: Camera2Session?) {
        val cap = setup ?: return
        session ?: return
        val now = SystemClock.elapsedRealtime()
        val since = if (lastMs == 0L) INTERVAL_MS else now - lastMs
        if (since < INTERVAL_MS) return
        val luma = LumaPlaneMath.grid(plane)?.first?.average() ?: return
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
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val INTERVAL_MS = 50L
        const val MAX_GAP_MS = 200L
        const val SEED_ISO = 400
    }
}
