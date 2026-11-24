package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightMeteringTest {
    @Test
    fun evenSceneReadsAsItsMean() {
        assertEquals(100.0, HighlightMetering.effectiveLuma(FloatArray(100) { 100f }), 0.5)
    }

    @Test
    fun aBrightButUnclippedWindowReadsAsTheMean() {
        // 40 % of the frame is a bright window at 230 (below the clip level): the mean decides.
        val grid = FloatArray(1000) { if (it < 400) 230f else 30f }
        assertEquals(grid.average(), HighlightMetering.effectiveLuma(grid), 0.5)
    }

    @Test
    fun aClippedLightSourceReadsBrighterThanTheMean() {
        // 10 % clipped (255) in a dark surround: reading is lifted so the controller backs off.
        val grid = FloatArray(1000) { if (it < 100) 255f else 30f }
        assertTrue(HighlightMetering.effectiveLuma(grid) > grid.average() * 1.5)
    }

    @Test
    fun aFewClippedPixelsAreIgnored() {
        val grid = FloatArray(1000) { if (it < 15) 255f else 40f }
        assertEquals(grid.average(), HighlightMetering.effectiveLuma(grid), 0.5)
    }

    @Test
    fun theLiftIsCapped() {
        assertEquals(255.0 * HighlightMetering.MAX_FACTOR, HighlightMetering.effectiveLuma(FloatArray(100) { 255f }), 0.5)
    }

    @Test
    fun emptyGridReadsZero() {
        assertEquals(0.0, HighlightMetering.effectiveLuma(FloatArray(0)), 0.0)
    }
}
