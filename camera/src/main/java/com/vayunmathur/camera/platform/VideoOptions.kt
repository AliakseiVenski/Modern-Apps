package com.vayunmathur.camera.platform

import android.graphics.ImageFormat
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import com.vayunmathur.camera.domain.VideoFpsPolicy
import com.vayunmathur.camera.domain.VideoStabilizationPolicy
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.camera.platform.camera2.setIsoCap
import com.vayunmathur.camera.platform.camera2.setVideoParams
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User video choices, applied to the viewfinder and read when a recording starts. [fps] 0 means
 * automatic (60 when the lens and encoder allow it, else 30); [quality] is a
 * [com.vayunmathur.camera.domain.VideoQualityTier] name (null = best that works);
 * [stabilizationEnabled] asks for EIS on top of OIS where available; [hdr] records 10-bit HLG;
 * [isoLimit] caps the ISO the camera may use (0 = no cap); [bitrateMbps] pins the encoder bitrate
 * (0 = automatic, which adapts to motion, storage and heat).
 */
data class VideoTuning(
    val fps: Int = 0,
    val stabilizationEnabled: Boolean = true,
    val quality: String? = null,
    val hdr: Boolean = false,
    val isoLimit: Int = 0,
    val bitrateMbps: Int = 0,
)

/** What the selected lens reports it can record. */
data class VideoOptions(
    val fps: List<Int>,
    val stabilizationSupported: Boolean,
    val qualities: List<String>,
    val hdrSupported: Boolean,
    val isoStops: List<Int>,
    /** What the encoder for the chosen codec accepts, in Mbps (null = unknown, no slider). */
    val encoderBitrateMbps: IntRange?,
) {
    /** Slider range for the chosen resolution (null = auto resolves to the best one). */
    fun bitrateRangeMbps(qualityPref: String?): IntRange? = encoderBitrateMbps?.let { range ->
        val tier = qualityPref?.takeIf { it in qualities } ?: qualities.firstOrNull()
        val high = minOf(range.last, bitrateCapMbps(tier))
        if (high > range.first) range.first..high else null
    }
}

private const val MBPS = 1_000_000
private const val MIN_BITRATE_MBPS = 2
private const val MAX_BITRATE_MBPS = 200
private const val UHD_BITRATE_CAP_MBPS = 100
private const val LOWER_BITRATE_CAP_MBPS = 30

/** The target phone's encoder stalls above ~21 Mbps at 1080p, so lower resolutions stop at 30 and UHD at 100. */
internal fun bitrateCapMbps(tierName: String?): Int =
    if (tierName == "UHD") UHD_BITRATE_CAP_MBPS else LOWER_BITRATE_CAP_MBPS

/** The user's pinned bitrate for [tier] in bits per second, clamped to that resolution's cap; null = automatic. */
internal fun CameraViewModel.customBitrateBps(tier: com.vayunmathur.camera.domain.VideoQualityTier): Int? {
    val mbps = videoTuningMutable.value.bitrateMbps
    return if (mbps > 0) minOf(mbps, bitrateCapMbps(tier.name)) * MBPS else null
}

private fun encoderBitrateRangeMbps(mime: String): IntRange? = try {
    android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        .firstNotNullOfOrNull { it.getCapabilitiesForType(mime).videoCapabilities?.bitrateRange }
        ?.let { range ->
            val low = maxOf(MIN_BITRATE_MBPS, range.lower / MBPS)
            val high = minOf(MAX_BITRATE_MBPS, range.upper / MBPS)
            if (high > low) low..high else null
        }
} catch (e: IllegalArgumentException) {
    Log.status("VideoOptions", "Could not query encoder bitrate range", e)
    null
}

/** Resolutions offered in the picker, best first (480p is never useful next to 720p). */
internal val OFFERED_TIERS = listOf(
    com.vayunmathur.camera.domain.VideoQualityTier.UHD,
    com.vayunmathur.camera.domain.VideoQualityTier.FHD,
    com.vayunmathur.camera.domain.VideoQualityTier.HD,
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
    videoOptionsMutable.value = withContext(Dispatchers.Default) {
        val (cameraId, snapshot) = recordingCameraSnapshot() ?: return@withContext null
        val mime = videoMime(bindTimeSettings.value.videoCodec).first
        VideoOptions(
            fps = VideoFpsPolicy.fixedRates(snapshot.aeTargetFpsRanges.map { it.lower to it.upper }),
            stabilizationSupported = VideoStabilizationPolicy.choose(
                enabled = true,
                videoModes = snapshot.videoStabilizationModes,
                opticalModes = snapshot.opticalStabilizationModes,
                sdkInt = Build.VERSION.SDK_INT,
            ).any,
            qualities = OFFERED_TIERS
                .filter { tierFits(cameraId, snapshot, mime, it, VideoFpsPolicy.STANDARD) }
                .map { it.name },
            hdrSupported = hdrSupported(cameraId),
            isoStops = isoLimitStops(snapshot),
            encoderBitrateMbps = encoderBitrateRangeMbps(mime),
        )
    } ?: return
}

/** Highest ISO the lens can reach: the sensor range plus the post-raw digital boost when the HAL has one. */
internal fun LensCharacteristicsSnapshot.maxEffectiveIso(): Int {
    val sensorMax = sensitivityRange?.upper ?: return 0
    return com.vayunmathur.camera.domain.IsoSplit.maxIso(sensorMax, postRawBoostRange?.upper ?: 100)
}

/** ISO values offered as a cap: the usual stops inside the reachable range, plus its maximum. */
internal fun isoLimitStops(snapshot: LensCharacteristicsSnapshot): List<Int> {
    val range = snapshot.sensitivityRange ?: return emptyList()
    val max = snapshot.maxEffectiveIso()
    return listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)
        .filter { it >= range.lower && it < max }
        .plus(max)
}

/** The mime and the AV1/HEVC flags for [codec], falling back to AVC when the device lacks the encoder. */
internal fun videoMime(codec: VideoCodec): Triple<String, Boolean, Boolean> {
    val useAv1 = codec == VideoCodec.AV1 && CodecSupport.isHardwareAv1EncoderAvailable
    val useHevc = !useAv1 && codec != VideoCodec.AVC && CodecSupport.isHevcEncoderAvailable
    val mime = when {
        useAv1 -> android.media.MediaFormat.MIMETYPE_VIDEO_AV1
        useHevc -> android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> android.media.MediaFormat.MIMETYPE_VIDEO_AVC
    }
    return Triple(mime, useAv1, useHevc)
}

/** 10-bit HLG recording needs API 33, an HLG10 camera output profile and an HEVC Main10 encoder. */
internal fun CameraViewModel.hdrSupported(cameraId: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !CodecSupport.isHevcMain10EncoderAvailable) {
        return false
    }
    return try {
        val manager = app.getSystemService(CameraManager::class.java)
        manager?.getCameraCharacteristics(cameraId)
            ?.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?.supportedProfiles
            ?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) == true
    } catch (e: android.hardware.camera2.CameraAccessException) {
        Log.status("VideoOptions", "Could not read dynamic range profiles", e)
        false
    }
}

/** Whether [tier] at [fps] works on this lens and encoder (stream size, stream frame rate, bitrate range). */
internal fun CameraViewModel.tierFits(
    cameraId: String?,
    snapshot: LensCharacteristicsSnapshot?,
    mime: String,
    tier: com.vayunmathur.camera.domain.VideoQualityTier,
    fps: Int,
): Boolean {
    val privateSizes = snapshot?.streamConfig?.sizesFor(ImageFormat.PRIVATE).orEmpty()
    return (privateSizes.isEmpty() || privateSizes.any { it.width == tier.width && it.height == tier.height }) &&
        (cameraId == null || maxStreamFps(cameraId, Size(tier.width, tier.height)) >= fps) &&
        CodecSupport.supportsVideoBitrate(mime, tier.width, tier.height, fps, tier.bitrateFor(fps))
}

/** What a recording in one mode will use; the preview follows the same plan. */
internal data class VideoPlan(
    val fps: Int,
    val tier: com.vayunmathur.camera.domain.VideoQualityTier,
    val mime: String,
    val useAv1: Boolean,
    val useHevc: Boolean,
    val stabilization: com.vayunmathur.camera.domain.VideoStabilizationChoice,
    /** Record 10-bit HLG (HEVC Main10). */
    val hdr: Boolean,
    /** ISO the camera may not exceed; 0 = no cap. */
    val isoLimit: Int,
)

/**
 * Codec from the bind-time settings (HEVC when HDR is on), then resolution and rate. A chosen
 * resolution outranks the rate (it stays at 30 fps if 60 does not fit); on auto, rate comes first:
 * 60 fps when the lens, the PRIVATE stream size and the encoder all allow it (UHD60 before FHD60
 * before 30 fps), otherwise the next rate down. A tier must be a stream size the lens advertises, or
 * the session configure fails (UHD on a 1080p front lens).
 */
internal fun CameraViewModel.planVideo(
    mode: CameraMode,
    cameraId: String?,
    snapshot: LensCharacteristicsSnapshot?,
): VideoPlan {
    val tuning = videoTuningMutable.value
    val hdr = tuning.hdr && cameraId != null && hdrSupported(cameraId)
    val (mime, useAv1, useHevc) = if (hdr) {
        Triple(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC, false, true)
    } else {
        videoMime(bindTimeSettings.value.videoCodec)
    }
    val fixedRates = VideoFpsPolicy.fixedRates(snapshot?.aeTargetFpsRanges.orEmpty().map { it.lower to it.upper })
    val rates = VideoFpsPolicy.candidates(fixedRates, tuning.fps, cinematic = mode == CameraMode.CINEMATIC)
    val chosenTiers = tuning.quality?.let { name -> OFFERED_TIERS.dropWhile { it.name != name } }
    val choice = if (chosenTiers != null) {
        chosenTiers.firstNotNullOfOrNull { tier ->
            rates.firstOrNull { tierFits(cameraId, snapshot, mime, tier, it) }?.let { it to tier }
        }
    } else {
        rates.firstNotNullOfOrNull { rate ->
            com.vayunmathur.camera.domain.VideoQualityTier.entries
                .firstOrNull { tierFits(cameraId, snapshot, mime, it, rate) }?.let { rate to it }
        }
    }
    val maxIso = snapshot?.maxEffectiveIso() ?: 0
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
        hdr = hdr,
        isoLimit = if (tuning.isoLimit in 1 until maxIso) tuning.isoLimit else 0,
    )
}

private val VIDEO_PREVIEW_MODES = setOf(CameraMode.VIDEO, CameraMode.TIMELAPSE, CameraMode.CINEMATIC)

/**
 * Puts the planned frame rate and stabilization on the live viewfinder, so changing either shows
 * at once and the preview matches the recording. Photo and Slo-Mo modes get the plain preview.
 */
internal fun CameraViewModel.applyVideoPreviewParams() {
    val session = activeCamera2Session ?: return
    // A take runs on this session: its frame rate and stabilization are fixed until it ends (applied then).
    if (camera2RecordingState.sessionRecorder != null) return
    val mode = cameraModeMutable.value
    if (mode !in VIDEO_PREVIEW_MODES) {
        previewIsoCap.configure(null)
        session.setIsoCap(null, null)
        session.setVideoParams(null, null, false)
        return
    }
    val snapshot = session.characteristics
    val plan = planVideo(mode, snapshot.cameraId, snapshot)
    session.setVideoParams(plan.fps, plan.stabilization.videoMode, plan.stabilization.opticalOn)
    // The recording has no brightness meter in HDR, so the viewfinder shows no cap there either.
    val cap = if (plan.isoLimit > 0 && !plan.hdr) {
        IsoCapSetup(plan.fps, snapshot.sensitivityRange?.lower ?: 50, plan.isoLimit)
    } else {
        null
    }
    previewIsoCap.configure(cap)
    if (cap == null) session.setIsoCap(null, null)
}
