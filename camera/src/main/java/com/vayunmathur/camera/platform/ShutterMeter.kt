package com.vayunmathur.camera.platform

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.vayunmathur.camera.domain.ExposureSetting
import com.vayunmathur.camera.domain.ShutterPriorityController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

private const val METER_INTERVAL_MS = 250L
private const val METER_SAMPLE_STEP = 8
private const val DEFAULT_SEED_ISO = 400

/** ISO used by the shutter lock when automatic ISO was asked for but no metering stream could bind. */
internal const val FALLBACK_LOCK_ISO = 800

/** Metering state for the automatic shutter lock. Written on the bind thread and the meter thread. */
internal class ShutterMeterState {
    /** Set by the bind ladder: whether the rung being tried should include the metering stream. */
    var requested = false
    var analysis: ImageAnalysis? = null
    @Volatile var controller: ShutterPriorityController? = null
    /** The exposure the controller currently asks for; null when no metering stream is bound. */
    val exposure = MutableStateFlow<ExposureSetting?>(null)

    /** Latest metered mean luma, published at the controller's rate for the debug overlay. */
    val luma = MutableStateFlow<Double?>(null)
    var lastUpdateMs = 0L
    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "shutter-meter").apply { isDaemon = true } }
    }
}

/** Mean luma of the central part of a frame's Y plane, sampled sparsely so it costs next to nothing. */
internal class LumaMeter(private val onLuma: (Double) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            var sum = 0L
            var count = 0
            var y = image.height / 5
            while (y < image.height * 4 / 5) {
                var x = image.width / 5
                while (x < image.width * 4 / 5) {
                    sum += buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
                    count++
                    x += METER_SAMPLE_STEP
                }
                y += METER_SAMPLE_STEP
            }
            if (count > 0) onLuma(sum.toDouble() / count)
        } finally {
            image.close()
        }
    }
}

/** True when the shutter lock is on with automatic ISO, which needs the metering stream. */
internal fun CameraViewModel.shutterMeterWanted(): Boolean =
    shutterLockMutable.value.let { it.enabled && it.iso == 0 }

internal fun buildMeterAnalysis(): ImageAnalysis =
    ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

/** After a successful video bind: starts the controller when the metering stream is bound. */
internal fun CameraViewModel.startShutterMeter() {
    val state = shutterMeter
    state.controller = null
    state.exposure.value = null
    val analysis = state.analysis ?: return
    val fps = videoFpsTarget.takeIf { it > 0 } ?: return
    val stops = isoStopsMutable.value
    if (stops.isEmpty()) return
    val frameNanos = 1_000_000_000L / fps
    val aeTime = lastAeExposureNanos
    val aeIso = lastAeIso
    val seed = if (aeTime != null && aeIso != null) ExposureSetting(aeTime, aeIso)
    else ExposureSetting(frameNanos, DEFAULT_SEED_ISO)
    val controller = ShutterPriorityController(frameNanos, stops.first(), stops.last(), seed)
    state.controller = controller
    state.exposure.value = controller.current
    analysis.setAnalyzer(state.executor, LumaMeter { luma -> onMeteredLuma(luma) })
    applyManualControls()
}

internal fun CameraViewModel.stopShutterMeter() {
    val state = shutterMeter
    state.analysis?.clearAnalyzer()
    state.analysis = null
    state.controller = null
    state.exposure.value = null
    state.luma.value = null
}

private fun CameraViewModel.onMeteredLuma(luma: Double) {
    val state = shutterMeter
    val controller = state.controller ?: return
    val now = SystemClock.elapsedRealtime()
    if (now - state.lastUpdateMs < METER_INTERVAL_MS) return
    state.lastUpdateMs = now
    state.luma.value = luma
    state.exposure.value = controller.update(luma) ?: return
    ContextCompat.getMainExecutor(app).execute { applyManualControls() }
}
