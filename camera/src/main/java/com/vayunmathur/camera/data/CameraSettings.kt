package com.vayunmathur.camera.data

import android.content.Context
import android.location.LocationManager
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.VideoTuning
import com.vayunmathur.camera.platform.applyVideoPreviewParams
import com.vayunmathur.camera.platform.refreshZoomLevels
import com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.CodecSupport
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.MotionPhotoDuration
import com.vayunmathur.camera.platform.PhotoFormat
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.camera.platform.VideoCodec
import com.vayunmathur.camera.platform.applyManualControls
import com.vayunmathur.camera.platform.clearMotionFrames
import com.vayunmathur.camera.platform.currentCropAspectRatio
import com.vayunmathur.camera.platform.registerLevelSensor
import com.vayunmathur.camera.platform.unregisterLevelSensor

internal fun CameraViewModel.loadSettings() {
    loadCaptureSettings()
    loadPreferenceToggles()
    if (levelEnabledMutable.value) registerLevelSensor()
}

/** Flash/timer/aspect/codec/audio-device/save-target/last-capture restores. */
private fun CameraViewModel.loadCaptureSettings() {
    ds.getString("camera_flash")?.let {
        runCatching { flashModeMutable.value = FlashMode.valueOf(it) }
    }
    ds.getString("camera_timer")?.let {
        runCatching { timerDurationMutable.value = TimerDuration.valueOf(it) }
    }
    ds.getString("camera_aspect_ratio")?.let {
        runCatching { aspectRatioMutable.value = AspectRatioOption.valueOf(it) }
    }
    ds.getString("camera_video_codec")?.let {
        videoCodecMutable.value = try { VideoCodec.valueOf(it) } catch (_: Exception) {
            CodecSupport.defaultVideoCodec
        }
    }
    ds.getString("camera_photo_format")?.let {
        runCatching { photoFormatMutable.value = PhotoFormat.valueOf(it) }
    }
    videoTuningMutable.value = VideoTuning(
        fps = ds.getString("camera_video_fps")?.toIntOrNull() ?: 0,
        stabilizationEnabled = ds.getString("camera_video_stabilization")?.toBoolean() ?: true,
        quality = ds.getString("camera_video_quality")?.takeIf { it.isNotBlank() },
        hdr = ds.getString("camera_video_hdr")?.toBoolean() ?: false,
        isoLimit = ds.getString("camera_video_iso_limit")?.toIntOrNull() ?: 0,
        // Before the switch existed an ISO limit meant the lock was on.
        shutterLock = ds.getString("camera_video_shutter_lock")?.toBoolean()
            ?: ((ds.getString("camera_video_iso_limit")?.toIntOrNull() ?: 0) > 0),
        bitrateMbps = ds.getString("camera_video_bitrate_mbps")?.toIntOrNull() ?: 0,
    )
    ds.getString("camera_custom_zoom_levels")?.let { raw ->
        customZoomLevelsMutable.value = raw.split(",")
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it > 0f }
            .distinct()
            .sorted()
    }
    debug.enabled.value = ds.getString("camera_debug_overlay")?.toBoolean() ?: false
    rememberModeMutable.value = ds.getString("camera_remember_mode")?.toBoolean() ?: false
    if (rememberModeMutable.value) {
        ds.getString("camera_last_mode")?.let { runCatching { cameraModeMutable.value = CameraMode.valueOf(it) } }
    }
    ds.getString("camera_motion_duration")?.let {
        runCatching { motionPhotoDurationMutable.value = MotionPhotoDuration.valueOf(it) }
    }
    // Physical input device (id+type), not an AudioSource type. A stale
    // or malformed stored value parses to Auto, never crashes.
    ds.getString("camera_audio_input_device")?.let {
        audioInputDeviceMutable.value = AudioInputDeviceSelection.parse(it)
    }
    // Retired settings are never read again: the AudioSource-type choice
    // (camera_audio_source, e.g. a stale "CAMCORDER"/"MIC" value) and the
    // audio-codec choice (camera_audio_codec; Opus-only). Drop any stored
    // values so they cannot linger or be misread later; they are simply
    // deleted, never parsed.
    viewModelScope.launch { ds.removeKeys(listOf("camera_audio_codec", "camera_audio_source")) }
    loadSaveTarget()
    // Guard against a blank persisted value: Uri.parse("") yields a non-null
    ds.getString("camera_last_capture")?.takeIf { it.isNotBlank() }?.let { lastCaptureUriMutable.value = it.toUri() }
}

/** Boolean/float preference restores (location, grid, level, mic, zoom, mirror, NR/edge). */
private fun CameraViewModel.loadPreferenceToggles() {
    ds.getString("camera_location")?.let { locationEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_grid")?.let { gridEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_level")?.let { levelEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_mic_muted")?.let { micMutedMutable.value = it.toBoolean() }
    ds.getString("camera_zoom_ratio")?.toFloatOrNull()?.let { zoomRatioMutable.value = it }
    ds.getString("camera_mirror_front")?.let { mirrorFrontMutable.value = it.toBoolean() }
    ds.getString("camera_noise_reduction")?.let { noiseReductionEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_edge_sharpening")?.let { edgeSharpeningEnabledMutable.value = it.toBoolean() }
    // Absent key keeps the ViewModel default (on), so existing users see no change.
    ds.getString("camera_motion_photo_enabled")?.let { motionPhotoEnabledMutable.value = it.toBoolean() }
}

fun CameraViewModel.setFlashMode(mode: FlashMode) {
    flashModeMutable.value = mode
    viewModelScope.launch { ds.setString("camera_flash", mode.name) }
}

/**
 * Toggle horizontal mirroring of the front-camera preview and saved photo/video (issue #632).
 * Takes effect on the next capture; the preview updates immediately via the UI layer.
 * Mid-recording the value persists but the bound session keeps the old MirrorMode
 * (bindTimeSettings) until the recording stops — it never rebinds under a take.
 */
fun CameraViewModel.setMirrorFront(enabled: Boolean) {
    mirrorFrontMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_mirror_front", enabled.toString()) }
}

/**
 * Noise-reduction toggle (issue #662). Takes effect live via the capture path and
 * persists for the next session.
 */
fun CameraViewModel.setNoiseReductionEnabled(enabled: Boolean) {
    noiseReductionEnabledMutable.value = enabled
    applyManualControls()
    viewModelScope.launch { ds.setString("camera_noise_reduction", enabled.toString()) }
}

/**
 * Edge-sharpening toggle (issue #662). Takes effect live via the capture path and
 * persists for the next session.
 */
fun CameraViewModel.setEdgeSharpeningEnabled(enabled: Boolean) {
    edgeSharpeningEnabledMutable.value = enabled
    applyManualControls()
    viewModelScope.launch { ds.setString("camera_edge_sharpening", enabled.toString()) }
}

fun CameraViewModel.toggleTorch() {
    torchEnabledMutable.value = !torchEnabledMutable.value
}

fun CameraViewModel.setTimerDuration(duration: TimerDuration) {
    timerDurationMutable.value = duration
    viewModelScope.launch { ds.setString("camera_timer", duration.name) }
}

fun CameraViewModel.setAspectRatio(ratio: AspectRatioOption) {
    aspectRatioMutable.value = ratio
    // The Camera2 still size/crop follows this flow at capture time.
    viewModelScope.launch { ds.setString("camera_aspect_ratio", ratio.name) }
}

/** Cycles the aspect ratio 4:3 → 3:2 → 16:9 → 1:1 → 4:3 (top-bar icon). */
fun CameraViewModel.cycleAspectRatio() {
    val order = listOf(
        AspectRatioOption.RATIO_4_3,
        AspectRatioOption.RATIO_3_2,
        AspectRatioOption.RATIO_16_9,
        AspectRatioOption.RATIO_1_1
    )
    val next = order[(order.indexOf(aspectRatioMutable.value) + 1) % order.size]
    setAspectRatio(next)
}

fun CameraViewModel.setLocationEnabled(enabled: Boolean) {
    locationEnabledMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
}

fun CameraViewModel.setPhotoFormat(format: PhotoFormat) {
    photoFormatMutable.value = format
    viewModelScope.launch { ds.setString("camera_photo_format", format.name) }
}

fun CameraViewModel.setVideoCodec(codec: VideoCodec) {
    videoCodecMutable.value = codec
    viewModelScope.launch { ds.setString("camera_video_codec", codec.name) }
}

/**
 * Motion Photo on/off. Off takes plain stills always: no capture diversion
 * and no ring-buffer accumulation, whatever the length/ratio/format. Drops
 * any frames buffered while it was on so a later re-enable cannot attach a
 * clip from before the off period.
 */
fun CameraViewModel.setMotionPhotoEnabled(enabled: Boolean) {
    motionPhotoEnabledMutable.value = enabled
    if (!enabled) clearMotionFrames()
    viewModelScope.launch { ds.setString("camera_motion_photo_enabled", enabled.toString()) }
}

fun CameraViewModel.setVideoFps(fps: Int) {
    videoTuningMutable.update { it.copy(fps = fps) }
    applyVideoPreviewParams()
    viewModelScope.launch { ds.setString("camera_video_fps", fps.toString()) }
}

/** Recording resolution as a `VideoQualityTier` name; null = best that works. */
fun CameraViewModel.setVideoQuality(name: String?) {
    videoTuningMutable.update { it.copy(quality = name) }
    applyVideoPreviewParams()
    viewModelScope.launch { ds.setString("camera_video_quality", name ?: "") }
}

/** 10-bit HLG recording on/off. */
fun CameraViewModel.setVideoHdr(enabled: Boolean) {
    videoTuningMutable.update { it.copy(hdr = enabled) }
    applyVideoPreviewParams()
    viewModelScope.launch { ds.setString("camera_video_hdr", enabled.toString()) }
}

/** Locks the shutter at 1/fps so the frame rate holds in the dark; ISO then rises up to the limit. */
fun CameraViewModel.setVideoShutterLock(enabled: Boolean) {
    videoTuningMutable.update { it.copy(shutterLock = enabled) }
    applyVideoPreviewParams()
    viewModelScope.launch { ds.setString("camera_video_shutter_lock", enabled.toString()) }
}

/** Highest ISO the locked shutter may use while filming; 0 = as high as the lens goes. */
fun CameraViewModel.setVideoIsoLimit(iso: Int) {
    videoTuningMutable.update { it.copy(isoLimit = iso) }
    applyVideoPreviewParams()
    viewModelScope.launch { ds.setString("camera_video_iso_limit", iso.toString()) }
}

/** Pins the video bitrate in Mbps; 0 = automatic. Read when a recording starts. */
fun CameraViewModel.setVideoBitrateMbps(mbps: Int) {
    videoTuningMutable.update { it.copy(bitrateMbps = mbps) }
    viewModelScope.launch { ds.setString("camera_video_bitrate_mbps", mbps.toString()) }
}

fun CameraViewModel.setVideoStabilizationEnabled(enabled: Boolean) {
    videoTuningMutable.update { it.copy(stabilizationEnabled = enabled) }
    applyVideoPreviewParams()
    viewModelScope.launch { ds.setString("camera_video_stabilization", enabled.toString()) }
}

/**
 * User-configurable zoom stops as zoom ratios relative to the 1x lens (e.g. 0.5, 1, 3, 5).
 * Empty = automatic stops. Persisted so the pills match the lenses this device really has.
 */
fun CameraViewModel.setCustomZoomLevels(levels: List<Float>) {
    val cleaned = levels.filter { it > 0f }.distinct().sorted()
    customZoomLevelsMutable.value = cleaned
    refreshZoomLevels()
    viewModelScope.launch { ds.setString("camera_custom_zoom_levels", cleaned.joinToString(",")) }
}

fun CameraViewModel.setDebugOverlay(enabled: Boolean) {
    debug.enabled.value = enabled
    viewModelScope.launch { ds.setString("camera_debug_overlay", enabled.toString()) }
}

fun CameraViewModel.setRememberMode(enabled: Boolean) {
    rememberModeMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_remember_mode", enabled.toString()) }
}

internal fun CameraViewModel.saveLastMode(mode: CameraMode) {
    viewModelScope.launch { ds.setString("camera_last_mode", mode.name) }
}

/** Motion-Photo clip length (issue #712); applies to the next capture's ring buffer. */
fun CameraViewModel.setMotionPhotoDuration(duration: MotionPhotoDuration) {
    motionPhotoDurationMutable.value = duration
    viewModelScope.launch { ds.setString("camera_motion_duration", duration.name) }
}

fun CameraViewModel.setAudioInputDevice(selection: AudioInputDeviceSelection) {
    audioInputDeviceMutable.value = selection
    viewModelScope.launch { ds.setString("camera_audio_input_device", selection.encode()) }
}

fun CameraViewModel.toggleGrid() {
    gridEnabledMutable.value = !gridEnabledMutable.value
    viewModelScope.launch { ds.setString("camera_grid", gridEnabledMutable.value.toString()) }
}

fun CameraViewModel.toggleLevel() {
    levelEnabledMutable.value = !levelEnabledMutable.value
    if (levelEnabledMutable.value) registerLevelSensor() else unregisterLevelSensor()
    viewModelScope.launch { ds.setString("camera_level", levelEnabledMutable.value.toString()) }
}

/** Maps the 0..1 blur-strength UI value to the bokeh shader's blurScale multiplier. */
fun CameraViewModel.setBlurStrength(value: Float) {
    blurStrengthMutable.value = value.coerceIn(0f, 1f)
}

fun CameraViewModel.setExposureCompensation(value: Float) {
    exposureCompensationMutable.value = value
    val key = activeLensKey ?: return
    viewModelScope.launch { ds.setString("camera_exposure_comp_$key", value.toString()) }
}

/** The brightness slider is kept per lens: switching lens (or restarting) brings back that lens's setting. */
internal fun CameraViewModel.onActiveLens(key: String) {
    if (key == activeLensKey) return
    activeLensKey = key
    exposureCompensationMutable.value = ds.getString("camera_exposure_comp_$key")?.toFloatOrNull() ?: 0f
}

fun CameraViewModel.setWarmth(value: Float) {
    warmthMutable.value = value
}

fun CameraViewModel.setShadows(value: Float) {
    shadowsMutable.value = value
}

internal fun CameraViewModel.setLastCaptureUri(uri: Uri?) {
    lastCaptureUriMutable.value = uri
    viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
}

/** Clears the last-capture thumbnail when its item is gone; MediaStore queries skip trashed rows, direct id access does not. */
internal fun CameraViewModel.dropGoneLastCapture() {
    val uri = lastCaptureUriMutable.value ?: return
    viewModelScope.launch {
        val live = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { app.contentResolver.query(uri, null, null, null, null)?.use { it.count > 0 } }.getOrNull() ?: false
        }
        if (!live && lastCaptureUriMutable.value == uri) setLastCaptureUri(null)
    }
}

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.updateLocation() {
    if (!locationEnabledMutable.value) return
    try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (e: SecurityException) {
        Log.status("CameraViewModel", "Failed to read last known location", e)
    }
}
