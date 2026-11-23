package com.vayunmathur.camera.domain

/** What to request for stabilization: the video mode (null = leave the HAL default) and OIS. */
data class VideoStabilizationChoice(val videoMode: Int?, val opticalOn: Boolean) {
    val any: Boolean get() = videoMode != null || opticalOn
}

/** Picks the stabilization request from what the lens lists (see `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`). */
object VideoStabilizationPolicy {
    /** `CONTROL_VIDEO_STABILIZATION_MODE_ON`. */
    const val MODE_ON = 1

    /** `CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION` (API 33). */
    const val MODE_PREVIEW = 2

    /** `LENS_OPTICAL_STABILIZATION_MODE_ON`. */
    const val OPTICAL_ON = 1

    private const val PREVIEW_MODE_MIN_SDK = 33

    /**
     * Electronic stabilization when [enabled] (preview stabilization when the device lists it and
     * the SDK has it, else plain ON), with the lens OIS on top where available. Disabled or
     * unsupported means nothing is requested.
     */
    fun choose(
        enabled: Boolean,
        videoModes: List<Int>,
        opticalModes: List<Int>,
        sdkInt: Int,
    ): VideoStabilizationChoice {
        if (!enabled) return VideoStabilizationChoice(videoMode = null, opticalOn = false)
        val videoMode = when {
            // The SDK guard keeps a vendor HAL that reports the raw value on an older release
            // from being taken at its word.
            sdkInt >= PREVIEW_MODE_MIN_SDK && MODE_PREVIEW in videoModes -> MODE_PREVIEW
            MODE_ON in videoModes -> MODE_ON
            else -> null
        }
        return VideoStabilizationChoice(videoMode, opticalOn = OPTICAL_ON in opticalModes)
    }
}
