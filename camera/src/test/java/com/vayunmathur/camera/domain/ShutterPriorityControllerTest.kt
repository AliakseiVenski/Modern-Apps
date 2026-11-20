package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShutterPriorityControllerTest {
    private val frame = 16_666_667L

    private fun controller(startIso: Int = 400) =
        ShutterPriorityController(frame, isoMin = 50, isoMax = 3200, start = ExposureSetting(frame, startIso))

    @Test fun onTargetLumaChangesNothing() {
        assertNull(controller().update(ShutterPriorityController.TARGET_LUMA))
    }

    @Test fun darkSceneRaisesIsoAndKeepsShutterAtOneFrame() {
        val c = controller()
        var last = c.current
        repeat(30) { c.update(40.0)?.let { last = it } }
        assertEquals(frame, last.timeNanos)
        assertTrue(last.iso > 400)
    }

    @Test fun darkSceneStopsAtMaxIso() {
        val c = controller()
        repeat(100) { c.update(5.0) }
        assertEquals(3200, c.current.iso)
        assertEquals(frame, c.current.timeNanos)
    }

    @Test fun brightSceneLowersIsoThenShortensShutter() {
        val c = controller()
        repeat(100) { c.update(250.0) }
        assertEquals(50, c.current.iso)
        assertTrue(c.current.timeNanos < frame)
    }

    @Test fun updateReturnsNullOnceSettled() {
        val c = controller(startIso = 3200)
        repeat(100) { c.update(5.0) }
        assertNull(c.update(5.0))
    }

    @Test fun smallDriftInsideDeadbandIsIgnored() {
        val c = controller()
        assertNull(c.update(ShutterPriorityController.TARGET_LUMA * 1.03))
        assertNotNull(c.update(ShutterPriorityController.TARGET_LUMA * 0.7))
    }
}
