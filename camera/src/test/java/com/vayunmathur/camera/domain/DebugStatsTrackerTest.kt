package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugStatsTrackerTest {
    private fun feed(tracker: DebugStatsTracker, fps: Int, seconds: Double): DebugStats? {
        val stepNanos = 1_000_000_000L / fps
        val frames = (fps * seconds).toInt()
        var last: DebugStats? = null
        for (i in 0 until frames) {
            tracker.onFrame(i * stepNanos, exposureNanos = 8_000_000L, iso = 640, focalMm = 6.3f)?.let { last = it }
        }
        return last
    }

    @Test fun stableSixtyFpsMeasuresSixty() {
        val stats = assertNotNull(feed(DebugStatsTracker(), fps = 60, seconds = 1.0))
        assertTrue(kotlin.math.abs(stats.fps - 60f) < 0.5f, "fps=${stats.fps}")
        assertEquals(640, stats.iso)
        assertEquals(8_000_000L, stats.exposureNanos)
        assertEquals(6.3f, stats.focalMm)
    }

    @Test fun droppedFramesShowAsLowerFps() {
        val stats = assertNotNull(feed(DebugStatsTracker(), fps = 24, seconds = 1.0))
        assertTrue(stats.fps < 25f, "fps=${stats.fps}")
    }

    @Test fun nothingPublishedBeforeTheWindowCloses() {
        val tracker = DebugStatsTracker(windowNanos = 400_000_000L)
        assertNull(tracker.onFrame(0L, 1L, 100, 1f))
        assertNull(tracker.onFrame(16_666_667L, 1L, 100, 1f))
    }

    @Test fun resetStartsAFreshWindow() {
        val tracker = DebugStatsTracker(windowNanos = 100_000_000L)
        tracker.onFrame(0L, 1L, 100, 1f)
        tracker.reset()
        assertNull(tracker.onFrame(500_000_000L, 1L, 100, 1f))
    }
}
