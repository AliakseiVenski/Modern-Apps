package com.vayunmathur.camera.platform.camera2

import android.view.Surface

private const val STOP_TARGET_WAIT_SECONDS = 1L

/**
 * Video-viewfinder controls of [Camera2Session]: frame rate, stabilization and the ISO cap on the live
 * request, the encoder target of a take, and tap metering. Split from the session class to keep it
 * within the file-length budget; all state is still touched only on the session executor.
 */

/** Video modes: fixed fps and stabilization on the viewfinder request (nulls clear them). */
fun Camera2Session.setVideoParams(fps: Int?, stabilizationMode: Int?, opticalStabilization: Boolean) {
    executor.execute {
        controls = controls.copy(
            videoFps = fps,
            videoStabilizationMode = stabilizationMode,
            opticalStabilization = opticalStabilization,
        )
        issueRepeatingRequest()
    }
}

/** Video ISO cap exposure (AE off); nulls return to AE. Fed ~20 Hz by the preview's brightness meter. */
fun Camera2Session.setIsoCap(timeNs: Long?, iso: Int?) {
    executor.execute {
        controls = controls.copy(isoCapTimeNs = timeNs, isoCapIso = iso)
        issueRepeatingRequest()
    }
}


/** Shutter-priority AE: keeps [timeNs] as the exposure time and lets AE do the rest; null returns to plain AE. */
fun Camera2Session.setShutterPriority(timeNs: Long?) {
    executor.execute {
        controls = controls.copy(shutterPriorityNs = timeNs)
        issueRepeatingRequest()
    }
}

/** Zoom and ISO-cap exposure in one request, so a lens switch and its exposure change land on the same frame. */
fun Camera2Session.setZoomAndIsoCap(ratio: Float, timeNs: Long, iso: Int) {
    executor.execute {
        controls = controls.copy(zoomRatio = ratio, isoCapTimeNs = timeNs, isoCapIso = iso)
        issueRepeatingRequest()
    }
}

/** True while the user holds exposure (long press); the software ISO cap freezes with it. */
val Camera2Session.aeLocked: Boolean get() = controls.aeLock

/** Starts feeding the encoder surface: the repeating request gains it as a target. */
fun Camera2Session.startRecordingTarget() {
    executor.execute {
        recordingTarget = recorderReady
        issueRepeatingRequest()
    }
}

/** Stops feeding the encoder surface and returns once the request without it is submitted. */
fun Camera2Session.stopRecordingTargetBlocking() {
    val done = java.util.concurrent.CountDownLatch(1)
    try {
        executor.execute {
            recordingTarget = false
            issueRepeatingRequest()
            done.countDown()
        }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        return
    }
    done.await(STOP_TARGET_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
}

/**
 * Swaps in the encoder surface of the next take and reconfigures the capture session over the
 * already-open camera (a MediaCodec cannot encode a second file, so each take has its own surface).
 */
fun Camera2Session.rebindRecorder(surface: Surface?) {
    executor.execute {
        recordingTarget = false
        recorderSurface = surface
        val opened = device ?: return@execute
        if (state != SessionState.STREAMING) return@execute
        configureSession(opened, generation, reuseOutputs = true)
    }
}


/**
 * Tap metering: transforms a normalised viewfinder tap into AF + AE metering regions
 * (see [MeteringTransform]) and re-issues the request. One AF scan is triggered at the point and
 * released after [AF_HOLD_MS], so continuous AF then keeps tracking and refocuses when the subject leaves.
 * [lockAe] freezes the exposure after metering (long press).
 * [displayRotationDegrees] is the display rotation (0/90/180/270);
 * [mirrored] is true for a mirrored front preview.
 */
fun Camera2Session.tapToFocus(
    tapX: Float,
    tapY: Float,
    displayRotationDegrees: Int = 0,
    mirrored: Boolean = false,
    lockAe: Boolean = false,
) {
    executor.execute {
        val activeArray = Camera2Controls.activeArrayOf(snapshot)
        if (activeArray.width <= 0 || activeArray.height <= 0) return@execute
        val crop = Camera2Controls.currentCropRegion(controls, snapshot)
        val region = MeteringTransform.meteringRegionForTap(
            tapX = tapX,
            tapY = tapY,
            activeArray = activeArray,
            cropRegion = crop,
            sensorOrientationDegrees = snapshot.sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees,
            mirrored = mirrored,
        )
        com.vayunmathur.library.log.Log.status(
            "TapFocus",
            "tap x=$tapX y=$tapY lockAe=$lockAe zoom=${controls.zoomRatio} zoomRange=${snapshot.minZoomRatio}..${snapshot.maxZoomRatio} " +
                "activeArray=$activeArray crop=$crop sensorOrientation=${snapshot.sensorOrientationDegrees} " +
                "displayRotation=$displayRotationDegrees maxAf=${snapshot.maxAfRegions} region=$region",
        )
        controls = controls.copy(afRegion = region, aeRegion = region, aeLock = lockAe)
        issueRepeatingRequest()
        if (!lockAe) scanAutoFocus()
    }
}

/** Clears tap metering, returning AF/AE to continuous full-frame. */
fun Camera2Session.clearMetering() {
    executor.execute {
        afTapGeneration++
        controls = controls.copy(afRegion = null, aeRegion = null, aeLock = false, afAuto = false)
        issueRepeatingRequest()
    }
}


private fun Camera2Session.triggerAutoFocus(trigger: Int) {
    val session = captureSession ?: return
    val surface = previewSurface ?: return
    if (state != SessionState.STREAMING) return
    val targets = listOfNotNull(
        surface, recorderSurface.takeIf { recordingTarget },
        ringOutput?.surface, rawRingOutput?.surface, analysisOutput?.surface,
    )
    runCatching { session.triggerAutoFocus(Camera2Controls.resolve(controls, snapshot), targets, trigger) }
}


/** Tap scan in AF_MODE_AUTO (always scans), then back to continuous AF after [AF_HOLD_MS]. */
private fun Camera2Session.scanAutoFocus() {
    val generation = ++afTapGeneration
    controls = controls.copy(afAuto = true)
    issueRepeatingRequest()
    triggerAutoFocus(android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER_START)
    android.os.Handler(executor.looper).postDelayed({
        if (generation != afTapGeneration) return@postDelayed
        triggerAutoFocus(android.hardware.camera2.CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        // The tapped window must go too: left in place, AE keeps metering that small patch and
        // brightness jumps as the scene moves under it. A held exposure keeps its region.
        controls = controls.copy(
            afAuto = false,
            afRegion = null,
            aeRegion = if (controls.aeLock) controls.aeRegion else null,
        )
        issueRepeatingRequest()
    }, AF_HOLD_MS)
}


