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

/** What a recording in one mode will use; the preview follows the same plan. */
internal data class VideoPlan(
    val fps: Int,
    val tier: com.vayunmathur.camera.domain.VideoQualityTier,
    val mime: String,
    val useAv1: Boolean,
    val useHevc: Boolean,
    val stabilization: com.vayunmathur.camera.domain.VideoStabilizationChoice,
)

/**
 * Codec from the bind-time settings, then rate first and the best tier that works at it: 60 fps when
 * the lens, the PRIVATE stream size and the encoder all allow it (UHD60 before FHD60 before 30 fps),
 * otherwise the next rate down. A tier must be a stream size the lens advertises, or the session
 * configure fails (UHD on a 1080p front lens).
 */
internal fun CameraViewModel.planVideo(
    mode: CameraMode,
    cameraId: String?,
    snapshot: LensCharacteristicsSnapshot?,
): VideoPlan {
    val selectedCodec = bindTimeSettings.value.videoCodec
    val useAv1 = selectedCodec == VideoCodec.AV1 && CodecSupport.isHardwareAv1EncoderAvailable
    val useHevc = !useAv1 && selectedCodec != VideoCodec.AVC && CodecSupport.isHevcEncoderAvailable
    val mime = when {
        useAv1 -> android.media.MediaFormat.MIMETYPE_VIDEO_AV1
        useHevc -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    }
    val privateSizes = snapshot?.streamConfig?.sizesFor(ImageFormat.PRIVATE).orEmpty()
    val tuning = videoTuningMutable.value
    val fixedRates = VideoFpsPolicy.fixedRates(snapshot?.aeTargetFpsRanges.orEmpty().map { it.lower to it.upper })
    val tiers = com.vayunmathur.camera.domain.VideoQualityTier.entries
    fun fits(candidate: com.vayunmathur.camera.domain.VideoQualityTier, fps: Int): Boolean =
        (privateSizes.isEmpty() || privateSizes.any { it.width == candidate.width && it.height == candidate.height }) &&
            (cameraId == null || maxStreamFps(cameraId, Size(candidate.width, candidate.height)) >= fps) &&
            CodecSupport.supportsVideoBitrate(mime, candidate.width, candidate.height, fps, candidate.bitrateFor(fps))
    val choice = VideoFpsPolicy
        .candidates(fixedRates, tuning.fps, cinematic = mode == CameraMode.CINEMATIC)
        .firstNotNullOfOrNull { rate -> tiers.firstOrNull { fits(it, rate) }?.let { rate to it } }
    return VideoPlan(
        fps = choice?.first ?: VideoFpsPolicy.STANDARD,
        tier = choice?.second ?: com.vayunmathur.camera.domain.VideoQualityTier.FHD,
        mime = mime,
        useAv1 = useAv1,
        useHevc = useHevc,
        stabilization = VideoStabilizationPolicy.choose(
            // Cinematic exists for stabilized footage, so it stays on whatever the switch says.
            enabled = tuning.stabilizationEnabled || mode == CameraMode.CINEMATIC,
            videoModes = snapshot?.videoStabilizationModes.orEmpty(),
            opticalModes = snapshot?.opticalStabilizationModes.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
        ),
    )
}

private val VIDEO_PREVIEW_MODES = setOf(CameraMode.VIDEO, CameraMode.TIMELAPSE, CameraMode.CINEMATIC)

/**
 * Puts the planned frame rate and stabilization on the live viewfinder, so changing either shows
 * at once and the preview matches the recording. Photo and Slo-Mo modes get the plain preview.
 */
internal fun CameraViewModel.applyVideoPreviewParams() {
    val session = activeCamera2Session ?: return
    val mode = cameraModeMutable.value
    if (mode !in VIDEO_PREVIEW_MODES) {
        session.setVideoParams(null, null, false)
        return
    }
    val snapshot = session.characteristics
    val plan = planVideo(mode, snapshot.cameraId, snapshot)
    session.setVideoParams(plan.fps, plan.stabilization.videoMode, plan.stabilization.opticalOn)
}
