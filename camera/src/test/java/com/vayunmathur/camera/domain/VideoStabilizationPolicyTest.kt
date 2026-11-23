package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoStabilizationPolicyTest {
    private fun choose(enabled: Boolean, video: List<Int>, optical: List<Int>, sdk: Int) =
        VideoStabilizationPolicy.choose(enabled, video, optical, sdk)

    @Test fun disabledRequestsNothing() {
        val choice = choose(false, listOf(0, 1, 2), listOf(0, 1), 34)
        assertNull(choice.videoMode)
        assertFalse(choice.opticalOn)
        assertFalse(choice.any)
    }

    @Test fun previewStabilizationWinsOnApi33() {
        assertEquals(VideoStabilizationPolicy.MODE_PREVIEW, choose(true, listOf(0, 1, 2), emptyList(), 33).videoMode)
    }

    @Test fun plainOnWhenSdkIsOlderThanPreviewMode() {
        assertEquals(VideoStabilizationPolicy.MODE_ON, choose(true, listOf(0, 1, 2), emptyList(), 32).videoMode)
    }

    @Test fun plainOnWhenPreviewModeIsNotListed() {
        assertEquals(VideoStabilizationPolicy.MODE_ON, choose(true, listOf(0, 1), emptyList(), 34).videoMode)
    }

    @Test fun nothingWhenNoVideoModeIsListed() {
        assertNull(choose(true, listOf(0), emptyList(), 34).videoMode)
    }

    @Test fun opticalComesOnTopWhenListed() {
        val choice = choose(true, listOf(0, 1), listOf(0, 1), 34)
        assertTrue(choice.opticalOn)
        assertTrue(choice.any)
        assertFalse(choose(true, listOf(0, 1), listOf(0), 34).opticalOn)
    }
}
