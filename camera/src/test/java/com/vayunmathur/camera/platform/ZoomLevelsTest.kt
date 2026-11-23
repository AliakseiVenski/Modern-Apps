package com.vayunmathur.camera.platform

import org.junit.Test
import kotlin.test.assertEquals

class ZoomLevelsTest {
    @Test fun automaticStopsAddWideAndTeleWhereTheLensReachesThem() {
        assertEquals(
            listOf(".6" to 0.6f, "1x" to 1f, "3x" to 3f),
            resolveZoomLevels(emptyList(), minZoom = 0.6f, maxZoom = 8f),
        )
    }

    @Test fun automaticStopsSkipTeleBeyondTheMaxZoom() {
        assertEquals(listOf("1x" to 1f, "3x" to 3f), resolveZoomLevels(emptyList(), minZoom = 1f, maxZoom = 3f))
        assertEquals(listOf("1x" to 1f), resolveZoomLevels(emptyList(), minZoom = 1f, maxZoom = 2f))
    }

    @Test fun customStopsReplaceTheAutomaticOnes() {
        assertEquals(
            listOf(".5" to 0.5f, "1x" to 1f, "3x" to 3f, "5x" to 5f),
            resolveZoomLevels(listOf(0.5f, 1f, 3f, 5f), minZoom = 0.5f, maxZoom = 10f),
        )
    }

    @Test fun customStopsOutsideTheLensRangeAreDropped() {
        assertEquals(
            listOf("1x" to 1f, "3x" to 3f),
            resolveZoomLevels(listOf(0.5f, 1f, 3f, 10f), minZoom = 1f, maxZoom = 5f),
        )
    }

    @Test fun customStopsThatAllMissFallBackToOneX() {
        assertEquals(listOf("1x" to 1f), resolveZoomLevels(listOf(20f), minZoom = 1f, maxZoom = 5f))
    }
}
