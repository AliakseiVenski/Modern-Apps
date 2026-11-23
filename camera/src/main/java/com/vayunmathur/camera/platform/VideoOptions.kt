package com.vayunmathur.camera.platform

import android.graphics.ImageFormat
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import com.vayunmathur.camera.domain.VideoFpsPolicy
import com.vayunmathur.camera.domain.VideoStabilizationPolicy
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User video choices, read when a recording starts. [fps] 0 means automatic (60 when the lens and
 * encoder allow it, else 30); [stabilizationEnabled] asks for EIS on top of OIS where available.
 */
data class VideoTuning(
    val fps: Int = 0,
    val stabilizationEnabled: Boolean = true,
)

/** What the selected lens reports it can record. */
data class VideoOptions(
    val fps: List<Int>,
    val stabilizationSupported: Boolean,
)

private const val NANOS_PER_SECOND = 1_000_000_000L

/** The Camera2 id and characteristics of the lens a recording would use, or null when unavailable. */
internal fun CameraViewModel.recordingCameraSnapshot(): Pair<String, LensCharacteristicsSnapshot>? {
    val manager = app.getSystemService(CameraManager::class.java) ?: return null
    val facing = selectedLensMutable.value?.facing ?: currentLensFacing()
    val cameraId = CharacteristicsFacade.resolveCamera2Id(manager, facing) ?: return null
    val snapshot = CharacteristicsFacade.tryFromCameraManager(manager, cameraId)?.snapshot ?: return null
    return cameraId to snapshot
}

/** Highest fps the lens can stream [size] as a PRIVATE (encoder) output, or [Int.MAX_VALUE] when unknown. */
internal fun CameraViewModel.maxStreamFps(cameraId: String, size: Size): Int = try {
    val manager = app.getSystemService(CameraManager::class.java)
    val map = manager?.getCameraCharacteristics(cameraId)
        ?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val minFrameNanos = map?.getOutputMinFrameDuration(ImageFormat.PRIVATE, size) ?: 0L
    if (minFrameNanos > 0) (NANOS_PER_SECOND / minFrameNanos).toInt() else Int.MAX_VALUE
} catch (e: IllegalArgumentException) {
    Log.status("VideoOptions", "No frame duration for $size", e)
    Int.MAX_VALUE
} catch (e: android.hardware.camera2.CameraAccessException) {
    Log.status("VideoOptions", "Could not read frame durations", e)
    Int.MAX_VALUE
}

/** Reads the capabilities of the lens a recording would use so Settings offers only what it can do. */
suspend fun CameraViewModel.refreshVideoOptions() {
    val (_, snapshot) = withContext(Dispatchers.Default) { recordingCameraSnapshot() } ?: return
    videoOptionsMutable.value = VideoOptions(
        fps = VideoFpsPolicy.fixedRates(snapshot.aeTargetFpsRanges.map { it.lower to it.upper }),
        stabilizationSupported = VideoStabilizationPolicy.choose(
            enabled = true,
            videoModes = snapshot.videoStabilizationModes,
            opticalModes = snapshot.opticalStabilizationModes,
            sdkInt = Build.VERSION.SDK_INT,
        ).any,
    )
}
