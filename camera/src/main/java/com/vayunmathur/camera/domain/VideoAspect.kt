package com.vayunmathur.camera.domain

/** Frame shape of a recording. [portraitRatio] is width/height of the viewfinder box in portrait. */
enum class VideoAspect(val label: String, val portraitRatio: Float) {
    WIDE("16:9", 9f / 16f),
    STANDARD("4:3", 3f / 4f),
}
