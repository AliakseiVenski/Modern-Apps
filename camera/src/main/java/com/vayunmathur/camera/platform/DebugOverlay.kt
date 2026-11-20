package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ExtendableBuilder
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.flow.MutableStateFlow

private const val FPS_WINDOW_NS = 400_000_000L
private const val STAB_PREVIEW = 2

/** What the sensor actually did over the last window of frames, read from capture results. */
data class DebugStats(
    val fps: Float,
    val exposureNanos: Long,
    val iso: Int,
    val frameNanos: Long,
    val aeOn: Boolean,
    val physicalId: String?,
    val focalMm: Float,
    val stabilization: String,
)

/** What the current video bind asked for. */
data class VideoBindSummary(
    val resolution: String?,
    val codec: String,
    val hdr: Boolean,
    val bitrateMbps: Int,
    val fpsTarget: Int,
)

/** State behind the viewfinder debug overlay. The callback is cheap and idles while the overlay is off. */
internal class DebugOverlayState {
    val enabled = MutableStateFlow(false)
    val stats = MutableStateFlow<DebugStats?>(null)
    val bind = MutableStateFlow<VideoBindSummary?>(null)
    val callback = DebugCaptureCallback({ enabled.value }) { stats.value = it }

    fun reset() {
        stats.value = null
        bind.value = null
    }
}

internal class DebugCaptureCallback(
    private val enabled: () -> Boolean,
    private val publish: (DebugStats) -> Unit,
) : CameraCaptureSession.CaptureCallback() {
    private var windowStartNs = 0L
    private var frames = 0

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        if (!enabled()) {
            frames = 0
            return
        }
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (frames == 0) windowStartNs = timestamp
        frames++
        val span = timestamp - windowStartNs
        if (span < FPS_WINDOW_NS) return
        publish(
            DebugStats(
                fps = (frames - 1) * 1e9f / span,
                exposureNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                frameNanos = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                aeOn = result.get(CaptureResult.CONTROL_AE_MODE) != android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF,
                physicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                focalMm = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
                stabilization = stabilizationLabel(
                    result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                    result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                ),
            )
        )
        frames = 0
    }

    private fun stabilizationLabel(video: Int?, optical: Int?): String {
        val parts = listOfNotNull(
            when (video) {
                null, 0 -> null
                STAB_PREVIEW -> "EIS(preview)"
                else -> "EIS"
            },
            if (optical == 1) "OIS" else null,
        )
        return parts.joinToString("+").ifEmpty { "off" }
    }
}

/** Streams capture results of the video preview into the debug overlay. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun <T> CameraViewModel.attachDebugCallback(builder: ExtendableBuilder<T>) {
    try {
        Camera2Interop.Extender(builder).setSessionCaptureCallback(debug.callback)
    } catch (e: IllegalStateException) {
        Log.status("DebugOverlay", "Could not attach the debug capture callback", e)
    }
}
