package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.RawRingConfig
import com.vayunmathur.camera.platform.camera2.RawRingGate
import com.vayunmathur.camera.platform.camera2.ReprocessRingConfig
import com.vayunmathur.camera.platform.camera2.ReprocessRingGate
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera2 (Phase 2) viewfinder: a TextureView whose surface feeds a
 * [Camera2Session]. Rendered only when the preview-engine gate selects
 * Camera2 (PHOTO + gate on — see `PreviewEnginePolicy`); the CameraX
 * viewfinder path is untouched otherwise.
 *
 * The [modifier] carries the same graphicsLayer RenderEffects / clipping as
 * the CameraX preview, so warmth/bokeh grading composites identically over
 * this TextureView (EMBEDDED-style texture rendering under the overlay tree).
 *
 * Ownership: this composition owns the executor and the session; both are
 * torn down when it leaves composition (mode switch, lens flip, gate off).
 *
 * [cameraId] is the bind identity only (the selected lens's id, used for
 * the fallback-latch key). It is NEVER handed to CameraManager: the session
 * is opened with the id [Camera2IdResolver] maps from [lensFacing] over
 * `CameraManager.cameraIdList`. A mapping or characteristics failure is an
 * error path (one [onError] via the holder's one-shot gate, delivered after
 * composition), never an exception escaping composition.
 *
 * Startup ordering: the holder is keyed on [lensFacing] only, so the
 * background lens enumeration reconciling the startup-seeded lens (same
 * facing, corrected id) does NOT rebuild the session. The session itself is
 * built off the main thread ([Camera2PreviewSession.build] on
 * `Dispatchers.Default`): `cameraIdList`, per-id `LENS_FACING` reads and the
 * full characteristics snapshot are HAL binder calls and must not run
 * during composition on main, and nothing here waits on CameraX
 * (`ProcessCameraProvider`) initialisation at all.
 */
private const val ESTABLISHED_AFTER_MS = 2000L

@Composable
internal fun Camera2Preview(
    cameraId: String,
    lensFacing: LensFacing?,
    zoomRatio: Float,
    torchOn: Boolean,
    exposureCompEv: Float,
    flashSetting: com.vayunmathur.camera.domain.FlashSetting =
        com.vayunmathur.camera.domain.FlashSetting.OFF,
    onSessionChanged: (Camera2Session?) -> Unit = {},
    onError: (String) -> Unit = {},
    /** Called once when the bind is first requested (startup ordering log). */
    onBindRequested: () -> Unit = {},
    /** Plane consumer for the analysis stream; null = no analysis output. */
    analysisConsumer: com.vayunmathur.camera.platform.camera2.AnalysisConsumer? = null,
    /** 10-bit HLG viewfinder: SurfaceView + HDR window colour mode, preview-only session. */
    hdrPreview: Boolean = false,
    /** Video modes: lean preview-only session with the record template, so stabilization works live. */
    videoPreview: Boolean = false,
    /** Video modes: the encoder the session is configured with, so recording starts on this viewfinder. */
    recorder: com.vayunmathur.camera.platform.SessionRecorderBinding? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Release the camera while the app is stopped (screen lock, home) and open a fresh session on
    // return: the OS drops the device in the background, and a session left behind burns through the
    // profile ladder against a camera it may not open, ending on the bind-failed error.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var started by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) started = true
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) started = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!started) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    val sessionHolder = remember(lensFacing, hdrPreview, videoPreview, recorder) {
        Camera2PreviewSession(
            context, lensFacing, onError, if (hdrPreview) null else analysisConsumer, hdrPreview, videoPreview,
            recorder,
        )
    }
    if (hdrPreview) {
        DisposableEffect(Unit) {
            val window = (context as? android.app.Activity)?.window
            val previous = window?.colorMode
            window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
            onDispose { previous?.let { window?.colorMode = it } }
        }
    }
    var session by remember(sessionHolder) { mutableStateOf<Camera2Session?>(null) }
    var streaming by remember(sessionHolder) { mutableStateOf(false) }
    sessionHolder.onStreamingStarted = { streaming = true }
    // One timer per holder: the label must survive session rebuilds between profile attempts.
    var initDelayPassed by remember(sessionHolder) { mutableStateOf(false) }
    LaunchedEffect(sessionHolder) {
        kotlinx.coroutines.delay(300)
        initDelayPassed = true
    }
    // The viewfinder counts as established once frames have flowed for a while; a profile that streams
    // briefly and then dies resets [streaming] before that, so the label stays through every attempt.
    var established by remember(sessionHolder) { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        if (streaming) {
            kotlinx.coroutines.delay(ESTABLISHED_AFTER_MS)
            established = true
        }
    }
    var probing by remember(sessionHolder) { mutableStateOf(false) }
    // Only while probing profiles (first open, or a profile failed): a plain re-open stays silent.
    val showInit = initDelayPassed && !established && (sessionHolder.firstProbe || probing)
    val rebuildScope = androidx.compose.runtime.rememberCoroutineScope()
    // A configure/device failure before the first frame rebuilds the
    // session with the next, smaller output profile (the holder owns
    // the ladder); only exhausting it reports through onError.
    sessionHolder.rebuildHandler = {
        streaming = false
        probing = true
        rebuildScope.launch {
            // The HAL unregisters the device for a moment after a
            // configure death; give it time to come back before reopening.
            // A first failure retries the same profile quickly (cold HAL).
            kotlinx.coroutines.delay(sessionHolder.nextRebuildDelayMs)
            if (sessionHolder.isClosedForRebuild()) return@launch
            val rebuilt = sessionHolder.rebuildNext()
            session = rebuilt
            if (rebuilt != null) onSessionChanged(rebuilt)
        }
    }

    DisposableEffect(sessionHolder) {
        onDispose {
            onSessionChanged(null)
            sessionHolder.close()
        }
    }
    // Build the session off-main, then publish it. Init/mapping failures are
    // delivered here, after composition, exactly once: reporting them from
    // remember{} would mutate ViewModel state mid-composition and re-enter
    // this composable in the same pass.
    LaunchedEffect(sessionHolder) {
        onBindRequested()
        sessionHolder.build()
        session = sessionHolder.session
        if (session != null) onSessionChanged(session)
        sessionHolder.deliverPendingError()
    }
    LaunchedEffect(session, zoomRatio) { session?.setZoomRatio(zoomRatio) }
    LaunchedEffect(session, torchOn) { session?.setTorch(torchOn) }
    // Exposure compensation is applied by the screen's effect in EV stops (scaled to the lens's range);
    // the raw slider value must not be pushed here too, or the two writers fight over one control.
    LaunchedEffect(session, flashSetting) {
        session?.setFlashSetting(flashSetting)
    }

    val liveSession = session
    if (liveSession == null) {
        // Building (one frame) or failed bind: on failure the fallback latch
        // (signalled via onError) switches this bind to CameraX; until that
        // recomposition lands, black-fill instead of constructing another
        // Camera2PreviewSession.
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (showInit) CameraInitOverlay()
        }
        return
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (hdrPreview) {
            AndroidView(
                factory = { ctx ->
                    android.view.SurfaceView(ctx).also { view ->
                        liveSession.setDisplayRotationDegrees((view.display?.rotation ?: 0) * 90)
                        sessionHolder.bridge.attachTo(view)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { view ->
                        liveSession.setDisplayRotationDegrees(
                            (view.display?.rotation ?: 0) * 90,
                        )
                        sessionHolder.bridge.bufferSize?.let { buffer ->
                            // A fixed (16:9 video) buffer fills the box centre-cropped instead of stretching.
                            view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                                fillKeepingAspect(v as TextureView, r - l, b - t, buffer.width to buffer.height)
                            }
                        }
                        sessionHolder.bridge.attachTo(view)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showInit) CameraInitOverlay()
    }
}
