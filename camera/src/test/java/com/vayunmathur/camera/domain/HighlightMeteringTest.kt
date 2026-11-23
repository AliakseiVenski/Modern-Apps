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
    fun darkSurroundWithAClippedLightReadsBrighterThanItsMean() {
        // 90 % dark (30), 10 % clipped (255): mean 52, the clipped light must dominate.
        val grid = FloatArray(1000) { if (it < 100) 255f else 30f }
        val metered = HighlightMetering.effectiveLuma(grid)
        assertTrue(metered > 100.0, "metered $metered")
    }

    @Test
    fun aFewBrightPixelsBelowThePercentileAreIgnored() {
        // 1 % bright pixels sit above the 98th percentile: the mean decides.
        val grid = FloatArray(1000) { if (it < 10) 255f else 40f }
        assertEquals(HighlightMetering.effectiveLuma(grid), grid.average(), 0.5)
    }

    @Test
    fun emptyGridReadsZero() {
        assertEquals(0.0, HighlightMetering.effectiveLuma(FloatArray(0)), 0.0)
    }
}
