package com.vayunmathur.camera.platform

import android.view.Surface
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.domain.VideoQualityTier
import com.vayunmathur.camera.platform.camera2.AudioInputDevices
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.camera.platform.camera2.rebindRecorder
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the video encoder is built for. Anything in here changing means a new viewfinder session
 * (the encoder surface is one of its outputs); everything else the take needs is applied live.
 */
internal data class RecorderPlan(
    val tier: VideoQualityTier,
    val fps: Int,
    val mime: String,
    val useAv1: Boolean,
    val useHevc: Boolean,
    val hdr: Boolean,
)

internal fun VideoPlan.toRecorderPlan() = RecorderPlan(tier, fps, mime, useAv1, useHevc, hdr)

/** Encoder prepared ahead of a take: codec configured, input surface created, not yet started. */
internal class PreparedRecorder(val plan: RecorderPlan, val core: Camera2RecordingCore) {
    /** True once a take owns the core (the viewfinder session must not release it). */
    @Volatile
    var taken = false
}

/** Hands the viewfinder session an encoder surface to configure, and gives it back on release. */
internal class SessionRecorderBinding(private val viewModel: CameraViewModel, val plan: RecorderPlan) {
    fun prepare(): Surface? = viewModel.prepareRecorder(plan)
    fun release() = viewModel.releasePreparedRecorder()
}

/** The plan a recording in [mode] would use on the selected lens; null when no lens snapshot exists. */
internal fun CameraViewModel.recorderPlan(mode: CameraMode): RecorderPlan? {
    val (cameraId, snapshot) = recordingCameraSnapshot() ?: return null
    val plan = planVideo(mode, cameraId, snapshot).toRecorderPlan()
    effectiveVideoAspectMutable.value = plan.tier.aspect
    return plan
}

/** Prepares (or reuses) the encoder for [plan] and returns its input surface; null when a take owns it. */
internal fun CameraViewModel.prepareRecorder(plan: RecorderPlan): Surface? {
    val state = camera2RecordingState
    return synchronized(state) {
        val existing = state.prepared
        if (existing != null) {
            if (existing.taken) return@synchronized null
            if (existing.plan == plan) return@synchronized existing.core.preparedEncoderSurface()
            existing.core.release()
            state.prepared = null
        }
        val initialBps = customBitrateBps(plan.tier) ?: computeAdaptiveBitrateDecision(
            plan.tier, useAv1 = plan.useAv1, useHevc = plan.useHevc, fps = plan.fps,
        ).bitrateBps
        val core = Camera2RecordingCore(
            config = CustomRecordingConfig(
                width = plan.tier.width,
                height = plan.tier.height,
                fps = plan.fps,
                videoMime = plan.mime,
                hdr = plan.hdr,
            ),
            initialBitrateBps = { initialBps },
            ownership = RecordingSessionOwnership.EXTERNALLY_OWNED,
            audioInputDevices = { AudioInputDevices.enumerateInfos(app) },
        )
        val surface = core.prepareEncoderSurface()
        if (surface == null) {
            core.release()
            return@synchronized null
        }
        state.prepared = PreparedRecorder(plan, core)
        surface
    }
}

/** Releases the prepared encoder unless a take owns it. */
internal fun CameraViewModel.releasePreparedRecorder() {
    val state = camera2RecordingState
    synchronized(state) {
        val existing = state.prepared
        if (existing != null && !existing.taken) {
            existing.core.release()
            state.prepared = null
        }
    }
}

/**
 * Whether the live viewfinder session can record [plan] on its own: it was configured with an encoder
 * surface, and that encoder is the one prepared for this plan.
 */
internal fun CameraViewModel.sessionRecordingReady(plan: RecorderPlan): Boolean =
    sessionRecordingBlocker(plan) == null

/** Why a take cannot start on the viewfinder session (it then falls back to reopening the camera), or null. */
internal fun CameraViewModel.sessionRecordingBlocker(plan: RecorderPlan): String? {
    val session = activeCamera2Session ?: return "no live session"
    val prepared = camera2RecordingState.prepared ?: return "no prepared encoder"
    val surface = session.readyRecorderSurface ?: return "session has no configured encoder surface"
    return when {
        surface !== prepared.core.preparedEncoderSurface() -> "session surface is not the prepared encoder's"
        prepared.taken -> "encoder already taken"
        prepared.plan != plan -> "plan changed: prepared ${prepared.plan} now $plan"
        else -> null
    }
}

/** Re-arms the viewfinder session with a fresh encoder after a take (a MediaCodec encodes one file). */
internal fun CameraViewModel.rearmSessionRecorder() {
    val plan = camera2RecordingState.sessionPlan ?: return
    camera2RecordingState.sessionPlan = null
    camera2RecordingState.prepared = null
    viewModelScope.launch(Dispatchers.Default) {
        val session = activeCamera2Session ?: return@launch
        val surface = prepareRecorder(plan)
        if (surface == null) {
            Log.error("SessionRecorder", "Could not prepare the next take's encoder")
        }
        session.rebindRecorder(surface)
    }
}
