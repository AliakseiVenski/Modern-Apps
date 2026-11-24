package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShutterPriorityControllerTest {
    private val frame = 16_666_667L
    private val dt = 0.05

    private fun controller(startIso: Int = 400) =
        ShutterPriorityController(frame, isoMin = 50, isoMax = 3200, start = ExposureSetting(frame, startIso))

    @Test fun onTargetLumaChangesNothing() {
        assertNull(controller().update(ShutterPriorityController.TARGET_LUMA, dt))
    }

    @Test fun darkSceneRaisesIsoAndKeepsShutterAtOneFrame() {
        val c = controller()
        repeat(200) { c.update(40.0, dt) }
        assertEquals(frame, c.current.timeNanos)
        assertTrue(c.current.iso > 400)
    }

    @Test fun darkSceneStopsAtMaxIso() {
        val c = controller()
        repeat(400) { c.update(5.0, dt) }
        assertEquals(3200, c.current.iso)
        assertEquals(frame, c.current.timeNanos)
    }

    @Test fun brightSceneLowersIsoThenShortensShutter() {
        val c = controller()
        repeat(600) { c.update(250.0, dt) }
        assertEquals(50, c.current.iso)
        assertTrue(c.current.timeNanos < frame)
    }

    @Test fun updateReturnsNullOnceSettled() {
        val c = controller(startIso = 3200)
        repeat(400) { c.update(5.0, dt) }
        assertNull(c.update(5.0, dt))
    }

    @Test fun smallErrorInsideDeadbandIsIgnored() {
        assertNull(controller().update(ShutterPriorityController.TARGET_LUMA * 1.01, dt))
        assertNotNull(controller().update(60.0, dt))
    }

    @Test fun suddenDarkeningRampsWithoutJumps() {
        val c = controller()
        c.update(ShutterPriorityController.TARGET_LUMA, dt)
        var previous = c.current.iso
        var worstRatio = 1.0
        repeat(200) {
            c.update(20.0, dt)
            val iso = c.current.iso
            worstRatio = maxOf(worstRatio, iso.toDouble() / previous)
            previous = iso
        }
        assertTrue(c.current.iso > 400)
        assertTrue(worstRatio < 1.4, "largest per-update ISO change was $worstRatio")
    }

    @Test fun theFirstReadingCorrectsAtOnce() {
        val c = controller(startIso = 100)
        val first = c.update(15.0, dt)
        assertNotNull(first)
        assertTrue(first.iso > 1500, "first reading only reached ${first.iso}")
    }

    @Test fun aSceneCutIsFollowedQuickly() {
        val c = controller(startIso = 3000)
        repeat(60) { c.update(ShutterPriorityController.TARGET_LUMA, dt) }
        // Into a much brighter scene: well under a second (20 updates) to drop several stops.
        repeat(20) { c.update(230.0, dt) }
        assertTrue(c.current.iso < 800 || c.current.timeNanos < frame, "still at ${c.current}")
    }
}
