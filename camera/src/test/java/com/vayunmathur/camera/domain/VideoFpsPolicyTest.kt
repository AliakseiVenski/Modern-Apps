package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals

class VideoFpsPolicyTest {
    private val mixedRanges = listOf(10 to 30, 60 to 60, 30 to 30, 24 to 24, 120 to 120, 15 to 15)

    @Test fun fixedRatesKeepOnlyFixedRangesBetween24And60() {
        assertEquals(listOf(24, 30, 60), VideoFpsPolicy.fixedRates(mixedRanges))
    }

    @Test fun autoPrefersSixtyThenThirty() {
        assertEquals(listOf(60, 30, 24), VideoFpsPolicy.candidates(listOf(24, 30, 60), wanted = 0, cinematic = false))
    }

    @Test fun autoFallsBackToThirtyWhenNoSixty() {
        assertEquals(listOf(30, 24), VideoFpsPolicy.candidates(listOf(24, 30), wanted = 0, cinematic = false))
    }

    @Test fun userChoiceLeadsThenSixtyThenThirty() {
        assertEquals(listOf(24, 60, 30), VideoFpsPolicy.candidates(listOf(24, 30, 60), wanted = 24, cinematic = false))
    }

    @Test fun unsupportedUserChoiceIsDropped() {
        assertEquals(listOf(60, 30), VideoFpsPolicy.candidates(listOf(30, 60), wanted = 25, cinematic = false))
    }

    @Test fun cinematicStaysAtThirtyAndIgnoresUserChoice() {
        assertEquals(listOf(30, 24), VideoFpsPolicy.candidates(listOf(24, 30, 60), wanted = 60, cinematic = true))
    }

    @Test fun neverEmpty() {
        assertEquals(listOf(30), VideoFpsPolicy.candidates(emptyList(), wanted = 0, cinematic = false))
        assertEquals(listOf(30), VideoFpsPolicy.candidates(listOf(60), wanted = 0, cinematic = true))
    }
}
