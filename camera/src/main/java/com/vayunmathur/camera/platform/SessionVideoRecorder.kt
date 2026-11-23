package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.LiveBitrateController
import com.vayunmathur.camera.domain.SceneMotion
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.startRecordingTarget
import com.vayunmathur.camera.platform.camera2.stopRecordingTargetBlocking
import com.vayunmathur.library.log.Log
import java.io.File

/**
 * Recording on the viewfinder's own capture session: the encoder surface is already one of the session's
 * outputs (prepared ahead in [Camera2RecordingCore]), so starting a take only starts the codec and adds
 * that surface to the repeating request. No camera reopen, no black gap.
 */
internal class SessionVideoRecorder(
    private val core: Camera2RecordingCore,
    private val controller: LiveBitrateController,
    private val motionSupplier: () -> SceneMotion?,
    internal val session: Camera2Session,
) : VideoRecordingEngine {
    @Volatile private var running = false
    private var adaptationThread: Thread? = null
    private var outputFile: File? = null
    private var outcomeCallback: ((VideoRecordingOutcome) -> Unit)? = null

    // Start and stop exclude each other: a stop pressed while the (off-main) start runs waits for it.
    @Synchronized
    override fun start(request: VideoRecordingRequest, onFinalized: (VideoRecordingOutcome) -> Unit) {
        check(!running) { "SessionVideoRecorder already running" }
        outputFile = request.outputFile
        outcomeCallback = onFinalized
        val begin = android.os.SystemClock.elapsedRealtime()
        try {
            core.setupMuxerAndAudio(request.outputFile)
            val afterSetup = android.os.SystemClock.elapsedRealtime()
            core.startVideoCodec()
            // The prepared codec was configured with the bitrate known at prepare time; apply today's.
            runCatching { core.setVideoBitrate(controller.currentBitrateBps) }
            core.markSessionStreaming()
            running = true
            core.startDrain()
            core.startAudio()
            val beforeTarget = android.os.SystemClock.elapsedRealtime()
            session.startRecordingTarget()
            startAdaptationThread()
            Log.status(
                "SessionRecorder",
                "take start: setup ${afterSetup - begin} ms, codec+audio ${beforeTarget - afterSetup} ms",
            )
        } catch (e: Exception) {
            running = false
            core.release()
            throw e
        }
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        try {
            // Frames stop first, then the encoder is told the stream ends.
            session.stopRecordingTargetBlocking()
            core.signalVideoEos()
            core.stopAudio()
            core.joinDrainAndAudio()
            adaptationThread?.interrupt()
            core.finishMuxer()
        } catch (e: IllegalStateException) {
            Log.error("SessionRecorder", "stop() failed", e)
        } finally {
            core.release()
            val file = outputFile
            val callback = outcomeCallback
            outcomeCallback = null
            if (file != null && callback != null) {
                val validation = Camera2VideoRecordingEngine.validateOutput(file)
                if (validation.readable) {
                    callback(VideoRecordingOutcome(file))
                } else {
                    file.delete()
                    callback(
                        VideoRecordingOutcome(
                            file,
                            IllegalStateException("Session recording failed read-back validation: $validation"),
                        ),
                    )
                }
            }
        }
    }

    /** Every 2 s: ask the controller, apply via encoder parameters when it moves. */
    private fun startAdaptationThread() {
        adaptationThread = Thread({
            var tickMs = 0L
            while (running) {
                try {
                    Thread.sleep(LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                tickMs += LiveBitrateController.DEFAULT_EVALUATE_INTERVAL_MS
                val target = controller.evaluate(motionSupplier(), tickMs) ?: continue
                try {
                    core.setVideoBitrate(target)
                    Log.debug("SessionRecorder", "live bitrate -> $target")
                } catch (e: IllegalStateException) {
                    Log.error("SessionRecorder", "setParameters(KEY_BITRATE) failed", e)
                }
            }
        }, "SessionRecorderAdapt").apply { start() }
    }
}
