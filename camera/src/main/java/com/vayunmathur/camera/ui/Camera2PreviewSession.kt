package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.camera.platform.camera2.RawRingConfig
import com.vayunmathur.camera.platform.camera2.RawRingGate
import com.vayunmathur.camera.platform.camera2.ReprocessRingConfig
import com.vayunmathur.camera.platform.camera2.ReprocessRingGate
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executor + bridge + session for one resolved Camera2 id; built once via
 * [build] (off the main thread), closed once. Build never throws: a facing
 * with no `cameraIdList` match, or a guarded characteristics read that
 * fails, leaves [session] null and records the reason for
 * [deliverPendingError] (one-shot through [OnceErrorReporter], shared with
 * the session listener, so init and runtime errors together signal the
 * fallback latch at most once per holder).
 */
internal class Camera2PreviewSession(
    private val context: Context,
    private val lensFacing: LensFacing?,
    onError: (String) -> Unit = {},
    private val analysisConsumer: com.vayunmathur.camera.platform.camera2.AnalysisConsumer? = null,
    private val hdrPreview: Boolean = false,
    private val videoPreview: Boolean = false,
    /** Video modes: supplies the encoder surface the session is configured with, so a take starts on it. */
    private val recorder: com.vayunmathur.camera.platform.SessionRecorderBinding? = null,
) {
    val executor = Camera2Executor("Camera2Preview-${lensFacing?.name ?: "unknown"}")

    @Volatile
    var session: Camera2Session? = null
        private set

    @Volatile
    private var resolvedCameraId: String? = null

    @Volatile
    private var closed = false

    /** Invoked (on the session executor) when a pre-streaming failure should rebuild degraded. */
    @Volatile
    var rebuildHandler: (() -> Unit)? = null

    /** Invoked (on the session executor) when a session delivers its first frame. */
    @Volatile
    var onStreamingStarted: (() -> Unit)? = null

    /** Output-profile rung; advances only on pre-streaming failures. */
    /** True when no profile was saved yet for this lens: the first open probes the ladder. */
    val firstProbe: Boolean = !hdrPreview && !profilePrefs.contains(profileKey)

    @Volatile
    private var profileIndex = if (hdrPreview) 0 else savedProfile()

    /** True once the current session has streamed a frame (failures after this are real errors). */
    @Volatile
    private var streamed = false

    /** True once the current profile was already retried unchanged (see [nextRebuildDelayMs]). */
    @Volatile
    private var retriedCurrentProfile = false

    private val createdAtMs = android.os.SystemClock.elapsedRealtime()

    /**
     * Pause before the next rebuild. The first failure of a profile is often a cold-HAL hiccup, so it
     * is retried unchanged after a short pause; only a repeat failure waits for the HAL to settle
     * and degrades a rung.
     */
    val nextRebuildDelayMs: Long
        get() = if (retriedCurrentProfile) DEGRADE_DELAY_MS else RETRY_DELAY_MS

    /** One rebuild at a time: a dying session reports several errors for one failure. */
    private val rebuildInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var lastError: String? = null

    private var facadeSnapshot: com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot? = null

    /**
     * Preview size to ask for. A video viewfinder uses the encoder's 16:9: HALs only stabilize (EIS)
     * when the preview and the video stream agree, and a 4:3 preview beside a 16:9 recording does
     * not. The HDR SurfaceView cannot be scaled to fit, so it keeps the view's shape.
     */
    private fun previewRequest(width: Int, height: Int): FrameSize =
        if (videoPreview && !hdrPreview) FrameSize(VIDEO_PREVIEW_WIDTH, VIDEO_PREVIEW_HEIGHT) else FrameSize(width, height)

    val bridge: PreviewSurfaceBridge = PreviewSurfaceBridge { event ->
        val liveSession = session
        val liveCameraId = resolvedCameraId
        if (liveSession == null || liveCameraId == null) return@PreviewSurfaceBridge
        when (event) {
            is PreviewSurfaceEvent.Available -> liveSession.open(
                cameraId = liveCameraId,
                surface = event.surface,
                requestedSize = previewRequest(event.width, event.height),
            )
            is PreviewSurfaceEvent.SizeChanged -> {
                // No resize API on the Phase-2 session: reopen at the new
                // size so the stream is re-validated up front.
                liveSession.close()
                liveSession.open(
                    cameraId = liveCameraId,
                    surface = event.surface,
                    requestedSize = previewRequest(event.width, event.height),
                )
            }
            is PreviewSurfaceEvent.Destroyed -> liveSession.close()
        }
    }

    private val errorGate = OnceErrorReporter(onError)

    @Volatile
    private var pendingInitError: String? = null

    /**
     * Resolves the Camera2 id from [lensFacing] over `CameraManager` and
     * reads the characteristics snapshot — all HAL binder work, so it runs
     * on `Dispatchers.Default`, never on the composition/main thread, and
     * never awaits CameraX initialisation. Safe to call once; a close that
     * raced the build closes the freshly built session immediately.
     */
    suspend fun build() {
        if (session != null || closed) return
        var built: Camera2Session? = null
        try {
            withContext(Dispatchers.Default) {
                val manager = context.getSystemService(CameraManager::class.java)
                    ?: throw IllegalStateException("no CameraManager")
                when (
                    val resolution = Camera2IdResolver.resolve(
                        lensFacing,
                        CharacteristicsFacade.cameraIdFacings(manager),
                    )
                ) {
                    is Camera2IdResolution.Unavailable -> pendingInitError = resolution.reason
                    is Camera2IdResolution.Resolved -> {
                        resolvedCameraId = resolution.cameraId
                        val facade = CharacteristicsFacade.tryFromCameraManager(
                            manager, resolution.cameraId,
                        ) ?: throw IllegalArgumentException(
                            "getCameraCharacteristics failed for Camera2 id ${resolution.cameraId}",
                        )
                        facadeSnapshot = facade.snapshot
                        built = buildSessionForProfile(facade.snapshot, manager)
                    }
                }
            }
        } catch (e: RuntimeException) {
            // Any characteristics-fetch failure — an unknown-device error
            // or a HAL binder failure — is an error path here; it must
            // never escape composition as a thrown exception.
            pendingInitError = "Camera2 preview init failed for facing $lensFacing: ${e.message}"
        }
        if (closed) {
            built?.close()
            return
        }
        session = built
    }

    /**
     * Builds one session for the current output profile. Optional
     * outputs are shed rung by rung (JPEG_R + RAW ring, then the YUV
     * ring, then analysis): some HALs refuse the full combination at
     * configure time (a max-res JPEG_R or RAW stream beside the max-res
     * JPEG), and a degraded live preview beats a dead one — the still
     * routes re-check what the session actually configured.
     */
    private fun buildSessionForProfile(
        snapshot: com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot,
        manager: CameraManager,
    ): Camera2Session {
        streamed = false
        val driver = AndroidCamera2Driver(
            manager,
            android.os.Handler(executor.looper),
        )
        // Video viewfinder ladder: 0 = analysis + encoder, 1 = encoder only, 2 = analysis only (the take
        // then falls back to the engine that reopens the camera), 3 = neither.
        val withRecorder = videoPreview && profileIndex < RECORDER_MAX_PROFILE && recorder != null
        val withAnalysis = if (videoPreview) profileIndex != 1 && profileIndex < 3 else profileIndex < 3
        val recorderSurface = if (withRecorder) recorder?.prepare() else null
        return Camera2Session(
            driver = driver,
            executor = executor,
            snapshot = snapshot,
            ringConfig = ReprocessRingConfig(enabled = profileIndex < 2 && ReprocessRingGate.ENABLED),
            rawRingConfig = RawRingConfig(enabled = profileIndex < 1 && RawRingGate.ENABLED),
            analysisConsumer = if (withAnalysis) analysisConsumer else null,
            initialRecorderSurface = recorderSurface,
            ultraHdrEnabled = profileIndex < 1,
            hdrPreview = hdrPreview,
            videoPreview = videoPreview,
            listener = object : Camera2Session.Listener {
                override fun onStreaming(previewSize: com.vayunmathur.camera.platform.camera2.FrameSize) {
                    streamed = true
                    if (!hdrPreview) saveProfile(profileIndex)
                    onStreamingStarted?.invoke()
                    Log.status(
                        "Camera2Preview",
                        "streaming after ${android.os.SystemClock.elapsedRealtime() - createdAtMs} ms " +
                            "(profile $profileIndex, retried=$retriedCurrentProfile)",
                    )
                }

                override fun onError(message: String) {
                    Log.status("Camera2Preview", message)
                    lastError = message
                    // A fatal session/device error rebuilds one rung
                    // down instead of surfacing: a HAL can even report
                    // configure success and then die before the first
                    // frame, so "streaming" is not proof of health.
                    // Only exhausting the ladder reports through.
                    if (!closed && profileIndex < MAX_PROFILE_INDEX &&
                        rebuildInFlight.compareAndSet(false, true)
                    ) {
                        Log.status(
                            "Camera2Preview",
                            "profile $profileIndex failed; rebuilding degraded",
                        )
                        rebuildHandler?.invoke()
                    } else if (profileIndex >= MAX_PROFILE_INDEX) {
                        errorGate.report(message)
                    }
                }
            },
        )
    }

    /** Closes the failed session and builds the next profile rung; null when exhausted/closed. */
    suspend fun rebuildNext(): Camera2Session? {
        if (closed || profileIndex >= MAX_PROFILE_INDEX) {
            rebuildInFlight.set(false)
            return null
        }
        if (retriedCurrentProfile) {
            profileIndex++
            retriedCurrentProfile = false
        } else {
            retriedCurrentProfile = true
        }
        val snapshot = facadeSnapshot ?: return null
        session?.close()
        session = null
        val built = withContext(Dispatchers.Default) {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@withContext null
            buildSessionForProfile(snapshot, manager)
        }
        if (closed) {
            built?.close()
            rebuildInFlight.set(false)
            return null
        }
        if (built == null && !closed) {
            errorGate.report(lastError ?: "Camera2 preview failed to build")
        }
        rebuildInFlight.set(false)
        session = built
        // The bridge will not re-fire Available for the existing
        // texture, so open the rebuilt session on the live surface.
        val liveSurface = bridge.state.surface
        val cameraId = resolvedCameraId
        if (built != null && liveSurface != null && cameraId != null) {
            built.open(
                cameraId = cameraId,
                surface = liveSurface,
                requestedSize = previewRequest(bridge.state.width, bridge.state.height),
            )
        }
        return built
    }

    private val profilePrefs get() = context.getSharedPreferences("camera2_preview_profile", Context.MODE_PRIVATE)

    // Keyed on the install time so an app update re-probes the richer profiles.
    private val profileKey: String
        get() {
            val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }
                .getOrDefault(0L)
            return "${lensFacing?.name}_${if (videoPreview) "video2" else "photo"}_$updated"
        }

    /** Profile that streamed last time on this lens, so a HAL that rejects the richer ones is not probed every launch. */
    private fun savedProfile(): Int = profilePrefs.getInt(profileKey, 0).coerceIn(0, MAX_PROFILE_INDEX)

    private fun saveProfile(index: Int) = profilePrefs.edit().putInt(profileKey, index).apply()

    /** Delivers a recorded init failure to onError, at most once. */
    fun deliverPendingError() {
        val message = pendingInitError ?: return
        pendingInitError = null
        Log.status("Camera2Preview", message)
        errorGate.report(message)
    }

    /** True after [close]; pending rebuilds must not run. */
    fun isClosedForRebuild(): Boolean = closed

    fun close() {
        closed = true
        session?.close()
        session = null
        // After the session: its encoder output must not vanish under an open capture session.
        recorder?.release()
        executor.shutdown()
    }

    private companion object {
        /** Last degrade rung (preview + JPEG only). */
        const val MAX_PROFILE_INDEX = 3
        const val RECORDER_MAX_PROFILE = 2
        const val VIDEO_PREVIEW_WIDTH = 1920
        const val VIDEO_PREVIEW_HEIGHT = 1080
        const val RETRY_DELAY_MS = 500L
        const val DEGRADE_DELAY_MS = 1000L
    }
}
