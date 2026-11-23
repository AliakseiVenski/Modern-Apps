package com.vayunmathur.camera.platform

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.vayunmathur.camera.domain.ExposureSetting
import com.vayunmathur.camera.domain.LiveBitrateController
import com.vayunmathur.camera.domain.ShutterPriorityController
import com.vayunmathur.camera.domain.SceneMotion
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.library.log.Log
import java.io.File

/** Configuration for one custom-path recording. AVC/HEVC/AV1 video + Opus audio only. */
data class CustomRecordingConfig(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    /**
     * Rate frames are captured at, when it differs from the playback
     * [fps] (Phase-5 slo-mo: 120/240 captured, 30 played back). Equal to
     * [fps] for normal recording; the encoder is then configured with
     * `KEY_CAPTURE_RATE = captureFps`, `KEY_FRAME_RATE = fps`.
     */
    val captureFps: Int = fps,
    val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    // Audio is always Opus (AudioEncoderPolicy.OPUS_MIME): there is no
    // audio-mime field here because there is no second option to select,
    // and a device without an Opus encoder records video-only instead.
    val audioSampleRate: Int = 48_000,
    val audioBitrateBps: Int = 128_000,
    val iFrameIntervalSec: Int = 1,
    /**
     * False records video only (slo-mo's contract, SloMoEnginePolicy
     * piece 5): no AudioRecord/Opus codec is created and the muxer starts
     * on the video track alone ([MuxerStartPolicy]). Also the effective
     * state when no Opus encoder exists (see [AudioEncoderPolicy]).
     */
    val audioEnabled: Boolean = true,
    /**
     * Physical microphone to prefer (Auto = system routing). Applied to
     * the AudioRecord via `setPreferredDevice` in the recording core; the
     * internal AudioSource stays CAMCORDER regardless of this choice.
     */
    val audioInputDevice: com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection =
        com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection.AUTO,
    /**
     * `CONTROL_VIDEO_STABILIZATION_MODE` to request (see `VideoStabilizationPolicy`), or null to
     * leave the HAL default, which is unstabilized for TEMPLATE_RECORD.
     */
    val stabilizationMode: Int? = null,
    /** Also request `LENS_OPTICAL_STABILIZATION_MODE_ON` (OIS under the electronic stabilization). */
    val opticalStabilization: Boolean = false,
    /**
     * Record 10-bit HLG: HEVC Main10 with BT.2020/HLG tags, and the encoder and preview outputs in the
     * HLG10 camera dynamic-range profile (API 33). No analysis stream is attached (an 8-bit YUV stream
     * cannot share the session), so motion-adaptive bitrate and the ISO cap sit idle.
     */
    val hdr: Boolean = false,
    /** Highest ISO the camera may use (0 = no cap): software AE keeps 1/fps shutter and moves ISO up to this. */
    val isoLimit: Int = 0,
    /** Lowest ISO of the lens, the floor of the ISO cap controller. */
    val isoMin: Int = 50,
    /** Sensor ISO ceiling and post-raw boost ceiling (100 = 1x): ISO above the first goes out as digital boost. */
    val isoSensorMax: Int = Int.MAX_VALUE,
    /** `CONTROL_ZOOM_RATIO` at start: the lens the user picked (a multi-camera switches lens on the ratio). */
    val zoomRatio: Float = 1f,
    /** Clockwise rotation players apply to the frames (MP4 orientation hint): the sensor is mounted rotated. */
    val orientationDegrees: Int = 0,
    val isoBoostMax: Int = 100,
)

/** Read-back summary of a finished file (see [Camera2VideoRecordingEngine.validateOutput]). */
data class CustomRecordingValidation(
    val readable: Boolean,
    val trackCount: Int,
    val hasVideoTrack: Boolean,
    val durationUs: Long,
)

/**
 * Custom recording path: Camera2 capture session feeding a MediaCodec encoder
 * input surface (plus the caller's preview surface), AudioRecord feeding an
 * audio MediaCodec, and a MediaMuxer writing the MP4. It exists for the one
 * thing the CameraX Recorder cannot do: re-targeting the encoder mid-recording
 * via `MediaCodec.setParameters(KEY_BITRATE)`, driven here by a
 * [LiveBitrateController] over the same motion estimator as the CameraX path.
 *
 * Ownership contract: this recorder opens the camera device itself, so the
 * CameraX session must be torn down before [start] and rebuilt after [stop].
 * It supports SDR AVC/HEVC + Opus only — no HLG, no vendor stabilization/EIS
 * semantics, no AV1; [RecordingPathPolicy] keeps those recordings on CameraX.
 *
 * VERIFICATION STATUS: compile-checked and unit-tested at the policy/controller
 * level only. It has NOT been device-verified: before [CustomRecordingGate] is
 * ever flipped, a device run must record on this path and validate the output
 * with [validateOutput] (plus external ffprobe) — an unverified muxer/encoder
 * path must never be the only copy of a user's recording.
 */
class Camera2VideoRecordingEngine(
    private val context: Context,
    private val config: CustomRecordingConfig,
    private val controller: LiveBitrateController,
    private val motionSupplier: () -> SceneMotion?,
    private val onMotionSample: (com.vayunmathur.camera.domain.FrameDiffSample) -> Unit,
    private val onError: (Exception) -> Unit,
    /** Sees every captured frame's sensor timestamp, exposure, ISO and focal length (debug overlay). */
    private val onFrameResult: ((timestampNs: Long, exposureNs: Long?, iso: Int?, focalMm: Float?) -> Unit)? = null,
) : VideoRecordingEngine {
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /** Size of the owned preview buffers (sensor orientation), so the view can keep their aspect. */
    @Volatile
    var ownedPreviewBufferSize: Pair<Int, Int>? = null
        private set
    private var analysisReader: android.media.ImageReader? = null
    private var ownedPreviewTexture: android.graphics.SurfaceTexture? = null
    private var ownedPreviewSurface: Surface? = null
    private var outcomeCallback: ((VideoRecordingOutcome) -> Unit)? = null
    private var previousGrid: FloatArray? = null
    private var previousGridWidth = 0
    private var lastAnalysisAtMs = 0L

    /** The live repeating request, kept so the ISO cap can re-issue it with a new exposure. */
    private var requestBuilder: CaptureRequest.Builder? = null

    /** Software AE behind the ISO cap; null when no cap is set (or HDR, which has no brightness meter). */
    private var isoController: ShutterPriorityController? = null
    private var lastLumaAtMs = 0L

    /** The engine-owned preview surface (for a TextureView to render), once started. */
    fun ownedPreviewSurface(): Surface? = ownedPreviewSurface

    /** The engine-owned preview texture a TextureView adopts while recording. */
    fun ownedPreviewTexture(): android.graphics.SurfaceTexture? = ownedPreviewTexture

    @Volatile private var previewTextureBorrowed = false

    /** Marks the owned preview texture as adopted by a TextureView (it releases it). */
    fun markPreviewTextureBorrowed() {
        previewTextureBorrowed = true
    }

    override fun start(request: VideoRecordingRequest, onFinalized: (VideoRecordingOutcome) -> Unit) {
        val cameraId = request.cameraId
        if (cameraId == null) {
            onFinalized(
                VideoRecordingOutcome(
                    request.outputFile,
                    IllegalArgumentException("Camera2 engine requires a cameraId")
                )
            )
            return
        }
        outcomeCallback = onFinalized
        start(request.outputFile, cameraId, request.previewSurface)
    }

    /** Shared codec/muxer/drain core (piece 2); this engine owns the session, the core does not. */
    private val core = Camera2RecordingCore(
        config,
        initialBitrateBps = { controller.currentBitrateBps },
        ownership = RecordingSessionOwnership.ENGINE_OWNED,
        audioInputDevices = {
            com.vayunmathur.camera.platform.camera2.AudioInputDevices.enumerateInfos(context)
        },
    )

    @Volatile private var running = false
    private var adaptationThread: Thread? = null
    private var outputFile: File? = null

    /** Opens the camera + encoders and starts recording into [outputFile]. */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File, cameraId: String, previewSurface: Surface?) {
        check(!running) { "Camera2VideoRecordingEngine already running" }
        this.outputFile = outputFile
        try {
            core.setupMuxerAndAudio(outputFile)
            openCamera(cameraId, previewSurface)
            running = true
            core.startDrain()
            core.startAudio()
            startAdaptationThread()
        } catch (e: Exception) {
            releaseAll()
            throw e
        }
    }

    /** Stops capture, drains the encoders, and finishes the muxed file. */
    override fun stop() {
        if (!running) return
        running = false
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            cameraDevice?.close()
            core.signalVideoEos()
            core.stopAudio()
            core.joinDrainAndAudio()
            adaptationThread?.interrupt()
            core.finishMuxer()
        } catch (e: IllegalStateException) {
            Log.error("Camera2Recorder", "stop() failed", e)
        } finally {
            releaseAll()
            // Never hand back a corrupt file: read it back, and on failure
            // delete it and report an error outcome instead of an output.
            val file = outputFile
            val callback = outcomeCallback
            outcomeCallback = null
            if (file != null && callback != null) {
                val validation = validateOutput(file)
                if (validation.readable) {
                    callback(VideoRecordingOutcome(file))
                } else {
                    file.delete()
                    callback(
                        VideoRecordingOutcome(
                            file,
                            IllegalStateException(
                                "Custom-path output failed read-back validation: $validation"
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * Codec-first split (SloMoEnginePolicy piece 1), delegated to the
     * shared core: configures the video codec and exposes its input
     * surface *before* any camera is opened, so a caller-owned session
     * (e.g. the constrained high-speed session) can list the surface as a
     * configure-time target. Idempotent; [start] reuses the prepared
     * surface instead of creating one in `openCamera`.
     */
    fun prepareEncoderSurface(): Surface? = core.prepareEncoderSurface()

    /** The surface exposed by [prepareEncoderSurface], or null before it. */
    fun preparedEncoderSurface(): Surface? = core.preparedEncoderSurface()

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, previewSurface: Surface?) {
        cameraThread = HandlerThread("Camera2RecorderCamera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val encoderSurface = core.preparedEncoderSurface() ?: prepareEncoderSurface()
            ?: throw IllegalStateException("encoder input surface unavailable")
        core.startVideoCodec()
        // Preview surface: the caller's when given, else an engine-owned
        // SurfaceTexture surface (exposed via ownedPreviewSurface() for a
        // TextureView to render once the UI migrates off CameraX preview).
        val effectivePreview = previewSurface ?: run {
            // Created detached (no GL texture name): a TextureView adopts it later, and one
            // still attached to a GL context renders black there.
            val texture = android.graphics.SurfaceTexture(false).apply {
                // Preview never needs encoder resolution: cap at 1080p so
                // the (encoder + preview + analysis) stream combination
                // stays within guaranteed HAL limits at UHD tiers.
                // Scaled down as a whole so the preview keeps the recording's shape (4:3 stays 4:3).
                val scale = minOf(1f, PREVIEW_MAX_WIDTH.toFloat() / config.width, PREVIEW_MAX_HEIGHT.toFloat() / config.height)
                val width = (config.width * scale).toInt()
                val height = (config.height * scale).toInt()
                setDefaultBufferSize(width, height)
                ownedPreviewBufferSize = width to height
            }
            ownedPreviewTexture = texture
            Surface(texture).also { ownedPreviewSurface = it }
        }
        // Analysis surface: same motion signal as the CameraX path, from an
        // ImageReader feeding the shared estimator through onMotionSample, and
        // the brightness meter of the ISO cap. HDR records without it: an 8-bit
        // YUV stream cannot share an HLG10 session.
        val reader = if (config.hdr) {
            null
        } else {
            android.media.ImageReader.newInstance(
                ANALYSIS_WIDTH, ANALYSIS_HEIGHT, android.graphics.ImageFormat.YUV_420_888, ANALYSIS_MAX_IMAGES
            )
        }
        analysisReader = reader
        reader?.setOnImageAvailableListener({ r -> onAnalysisFrame(r) }, cameraHandler)
        isoController = if (config.isoLimit > 0 && !config.hdr) {
            val frameNanos = NANOS_PER_SECOND / config.captureFps
            val isoMin = config.isoMin.coerceAtMost(config.isoLimit)
            ShutterPriorityController(
                frameNanos, isoMin, config.isoLimit,
                ExposureSetting(frameNanos, ISO_CAP_SEED_ISO.coerceIn(isoMin, config.isoLimit)),
            )
        } else {
            null
        }
        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                core.markSessionStreaming()
                try {
                    val device = cameraDevice ?: return
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(encoderSurface)
                        addTarget(effectivePreview)
                        reader?.let { addTarget(it.surface) }
                        set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(config.captureFps, config.captureFps)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            set(CaptureRequest.CONTROL_ZOOM_RATIO, config.zoomRatio)
                        }
                        // Explicit OFF when disabled: the HAL's template default may stabilize anyway.
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            config.stabilizationMode ?: CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                        )
                        // OIS is never forced off (see applyPreviewParams): the HAL default keeps lens OIS.
                        if (config.opticalStabilization) {
                            set(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                            )
                        }
                    }
                    requestBuilder = builder
                    isoController?.let { applyExposure(builder, it.current) }
                    session.setRepeatingRequest(builder.build(), frameResultCallback(), cameraHandler)
                } catch (e: IllegalStateException) {
                    // Device closed under us (a competing
                    // session took the camera): surface as an
                    // engine error, never an uncaught crash.
                    onError(e)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError(IllegalStateException("Custom recorder capture session failed to configure"))
            }
        }
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                try {
                    if (config.hdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val outputs = listOf(encoderSurface, effectivePreview).map { surface ->
                            android.hardware.camera2.params.OutputConfiguration(surface).apply {
                                dynamicRangeProfile = android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                            }
                        }
                        device.createCaptureSession(
                            android.hardware.camera2.params.SessionConfiguration(
                                android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                                outputs,
                                { task -> cameraHandler?.post(task) },
                                sessionCallback,
                            )
                        )
                    } else {
                        val surfaces = listOfNotNull(encoderSurface, effectivePreview, reader?.surface)
                        device.createCaptureSession(surfaces, sessionCallback, cameraHandler)
                    }
                } catch (e: IllegalStateException) {
                    onError(e)
                } catch (e: IllegalArgumentException) {
                    onError(e)
                }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                cameraDevice = null
                onError(IllegalStateException("Custom recorder camera error $error"))
            }
        }, cameraHandler)
        // Facing check kept explicit: callers pick the id, but a front camera
        // would need mirroring this path does not do.
        val facing = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_FACING)
        Log.debug("Camera2Recorder", "opening camera $cameraId facing=$facing")
    }

    /** Analysis frame: brightness for the ISO cap at ~20 Hz, scene motion for adaptive bitrate at ~5 Hz. */
    private fun onAnalysisFrame(r: android.media.ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val lumaDue = isoController != null && nowMs - lastLumaAtMs >= LUMA_INTERVAL_MS
            val motionDue = nowMs - lastAnalysisAtMs >= VIDEO_MOTION_SAMPLE_INTERVAL_MS
            if (!lumaDue && !motionDue) return
            val plane = image.planes.firstOrNull() ?: return
            val (grid, gridWidth) = sampleYPlaneGrid(
                plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height
            ) ?: return
            if (lumaDue) {
                val sinceMs = if (lastLumaAtMs == 0L) LUMA_INTERVAL_MS else nowMs - lastLumaAtMs
                lastLumaAtMs = nowMs
                isoController
                    ?.update(com.vayunmathur.camera.domain.HighlightMetering.effectiveLuma(grid), sinceMs.coerceAtMost(LUMA_MAX_GAP_MS) / 1000.0)
                    ?.let { applyExposureLive(it) }
            }
            if (motionDue) {
                lastAnalysisAtMs = nowMs
                val previous = previousGrid
                previousGrid = grid
                if (previous != null && previous.size == grid.size && previousGridWidth == gridWidth) {
                    com.vayunmathur.camera.domain.SceneMotionMath
                        .sample(previous, grid, gridWidth)
                        ?.let { onMotionSample(it) }
                }
                previousGridWidth = gridWidth
            }
        } finally {
            image.close()
        }
    }

    /** Manual exposure at one frame of shutter and a fixed frame duration: AE off, so ISO is ours to move. */
    private fun applyExposure(builder: CaptureRequest.Builder, setting: ExposureSetting) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, setting.timeNanos)
        val (sensorIso, boost) = com.vayunmathur.camera.domain.IsoSplit.split(setting.iso, config.isoSensorMax, config.isoBoostMax)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensorIso)
        if (boost > com.vayunmathur.camera.domain.IsoSplit.UNITY_BOOST) {
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
        }
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, NANOS_PER_SECOND / config.captureFps)
    }

    /** Live zoom / lens switch during the take: the HAL picks the lens from the ratio. */
    fun setZoomRatio(ratio: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val builder = requestBuilder ?: return
        val session = captureSession ?: return
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        try {
            session.setRepeatingRequest(builder.build(), frameResultCallback(), cameraHandler)
        } catch (e: IllegalStateException) {
            Log.status("Camera2Recorder", "zoom update skipped: session closed", e)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            Log.status("Camera2Recorder", "zoom update failed", e)
        }
    }

    private fun applyExposureLive(setting: ExposureSetting) {
        val builder = requestBuilder ?: return
        val session = captureSession ?: return
        applyExposure(builder, setting)
        try {
            session.setRepeatingRequest(builder.build(), frameResultCallback(), cameraHandler)
        } catch (e: IllegalStateException) {
            Log.status("Camera2Recorder", "ISO cap update skipped: session closed", e)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            Log.status("Camera2Recorder", "ISO cap update failed", e)
        }
    }

    /** Capture callback feeding [onFrameResult]; null when nobody listens, so no per-frame work is queued. */
    private fun frameResultCallback(): CameraCaptureSession.CaptureCallback? {
        val sink = onFrameResult ?: return null
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                val timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
                logStabilization(result)
                sink(
                    timestamp,
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                    result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let {
                        it * (result.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100) / 100
                    },
                    result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH),
                )
            }
        }
    }

    private var lastStabilizationLog: String? = null

    /** Logs what the HAL really applied for stabilization, once per change (the request may be ignored). */
    private fun logStabilization(result: android.hardware.camera2.TotalCaptureResult) {
        val applied = "recording requested video=${config.stabilizationMode} ois=${config.opticalStabilization} " +
            "result video=${result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)} " +
            "ois=${result.get(android.hardware.camera2.CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)} " +
            "physical=${result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)} " +
            "crop=${result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)} " +
            "zoom=${result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)}"
        if (applied == lastStabilizationLog) return
        lastStabilizationLog = applied
        Log.status("Stabilization", applied)
    }

    /** Every 2 s: ask the controller, apply via encoder parameters when it moves. */
    private fun startAdaptationThread() {
        adaptationThread = Thread({
            var tickMs = 0L
            while (running) {
                try {
                    Thread.sleep(LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    // stop() interrupts to end the loop: exit quietly,
                    // never as an uncaught exception.
                    break
                }
                tickMs += LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS
                val target = controller.evaluate(motionSupplier(), tickMs) ?: continue
                try {
                    core.setVideoBitrate(target)
                    Log.debug("Camera2Recorder", "live bitrate -> $target")
                } catch (e: IllegalStateException) {
                    Log.error("Camera2Recorder", "setParameters(KEY_BITRATE) failed", e)
                }
            }
        }, "Camera2RecorderAdapt").apply { start() }
    }

    private fun releaseAll() {
        try { analysisReader?.close() } catch (_: Exception) {}
        // A borrowed texture/surface pair is owned by the adopting
        // TextureView, which releases it on detach; releasing here too
        // would race the view still rendering the last frame.
        if (!previewTextureBorrowed) {
            try { ownedPreviewSurface?.release() } catch (_: Exception) {}
            try { ownedPreviewTexture?.release() } catch (_: Exception) {}
        }
        previewTextureBorrowed = false
        analysisReader = null
        requestBuilder = null
        isoController = null
        ownedPreviewSurface = null
        ownedPreviewTexture = null
        previousGrid = null
        core.release()
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
    }

    companion object {
        private const val PREVIEW_MAX_WIDTH = 1920
        private const val PREVIEW_MAX_HEIGHT = 1080
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        private const val ANALYSIS_MAX_IMAGES = 3
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Brightness meter cadence of the ISO cap: small, frequent exposure steps read as a smooth ramp. */
        private const val LUMA_INTERVAL_MS = 50L
        private const val LUMA_MAX_GAP_MS = 200L
        private const val ISO_CAP_SEED_ISO = 400

        /**
         * Read-back validation for a finished custom-path file: the extractor
         * must open it, find a video track, and report a positive duration.
         * Device-only (android.media is stubbed in unit tests) — callers run
         * this after [stop] before treating the file as the user's recording.
         */
        fun validateOutput(file: File): CustomRecordingValidation {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                var hasVideo = false
                var durationUs = 0L
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) hasVideo = true
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                    }
                }
                CustomRecordingValidation(
                    readable = extractor.trackCount > 0 && hasVideo && durationUs > 0,
                    trackCount = extractor.trackCount,
                    hasVideoTrack = hasVideo,
                    durationUs = durationUs,
                )
            } catch (e: Exception) {
                Log.error("Camera2Recorder", "validateOutput failed for ${file.name}", e)
                CustomRecordingValidation(false, 0, false, 0L)
            } finally {
                extractor.release()
            }
        }
    }
}
