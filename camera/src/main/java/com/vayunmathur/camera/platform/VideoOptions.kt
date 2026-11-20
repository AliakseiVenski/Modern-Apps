package com.vayunmathur.camera.platform

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import com.vayunmathur.library.log.Log

/** User video choices. Zero/null means automatic. Applied at bind time, best effort. */
data class VideoTuning(
    val quality: String? = null,
    val fps: Int = 0,
    val bitrateMbps: Int = 0,
    val hdr: Boolean = true,
)

/** Experimental: fixed exposure time of 1/fps at a fixed ISO while recording, so fps cannot sag in the dark. */
data class ShutterLock(val enabled: Boolean = false, val iso: Int = 800)

/** What the selected lens reports it can record. */
data class VideoOptions(
    val qualities: List<String>,
    val fps: List<Int>,
    val encoderBitrateMbps: IntRange?,
    val hdrSupported: Boolean,
) {
    /** Slider range for the chosen resolution (null = auto, which resolves to the best one). */
    fun bitrateRangeMbps(qualityPref: String?): IntRange? = encoderBitrateMbps?.let { range ->
        val high = minOf(range.last, bitrateCapMbps(effectiveQualityName(qualityPref, qualities)))
        if (high > range.first) range.first..high else null
    }
}

private const val MBPS = 1_000_000
private const val MIN_BITRATE_MBPS = 2
private const val MAX_BITRATE_MBPS = 200
private const val UHD_BITRATE_CAP_MBPS = 100
private const val LOWER_BITRATE_CAP_MBPS = 30

internal val VIDEO_QUALITY_NAMES = listOf("UHD", "FHD", "HD", "SD")

/** The encoder on the target phone stalls above ~21 Mbps at 1080p, so lower resolutions stop at 30. */
internal fun bitrateCapMbps(quality: String?): Int =
    if (quality == "UHD") UHD_BITRATE_CAP_MBPS else LOWER_BITRATE_CAP_MBPS

/** The quality a bind will land on: the preference when the lens has it, else the best available. */
internal fun effectiveQualityName(pref: String?, supported: List<String>): String? =
    pref?.takeIf { it in supported } ?: supported.firstOrNull()

internal fun supportedQualityNames(info: CameraInfo): List<String> = try {
    val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
    VIDEO_QUALITY_NAMES.filter { qualityFromName(it) in supported }
} catch (e: IllegalStateException) {
    Log.status("VideoOptions", "Could not query supported qualities", e)
    emptyList()
}

internal fun qualityFromName(name: String?): Quality? = when (name) {
    "UHD" -> Quality.UHD
    "FHD" -> Quality.FHD
    "HD" -> Quality.HD
    "SD" -> Quality.SD
    else -> null
}

private fun VideoCodec.mime(): String = when (this) {
    VideoCodec.AVC -> MediaFormat.MIMETYPE_VIDEO_AVC
    VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
    VideoCodec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
}

private fun encoderBitrateRangeMbps(mime: String): IntRange? = try {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
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

/** Reads the capabilities of the currently selected lens so Settings can offer only what it can do. */
suspend fun CameraViewModel.refreshVideoOptions() {
    val provider = cameraProvider ?: ProcessCameraProvider.awaitInstance(app)
    val info: CameraInfo = try {
        provider.getCameraInfo(lensSelector(lensFacingMutable.value, selectedLensMutable.value))
    } catch (e: IllegalArgumentException) {
        Log.status("VideoOptions", "No camera for the selected lens", e)
        return
    }
    videoOptionsMutable.value = VideoOptions(
        qualities = supportedQualityNames(info),
        fps = fixedFpsValues(info),
        encoderBitrateMbps = encoderBitrateRangeMbps(videoCodecMutable.value.mime()),
        hdrSupported = hlgSupportedByCamera(info),
    )
}
