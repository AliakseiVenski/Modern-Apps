package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveVideoBitrateFpsTest {
    private fun decide(tier: VideoQualityTier, fps: Int, motion: SceneMotion? = null) =
        AdaptiveVideoBitratePolicy.decide(
            AdaptiveBitrateInput(tier, motion, freeStorageBytes = null, thermal = ThermalPressure.NORMAL, boostAllowed = false, fps = fps)
        )

    @Test fun scaleIsNeutralUpToThirtyAndGrowsToSixty() {
        assertEquals(1.0, fpsBitrateScale(24))
        assertEquals(1.0, fpsBitrateScale(30))
        assertEquals(1.5, fpsBitrateScale(60))
        assertTrue(fpsBitrateScale(45) in 1.2..1.3)
    }

    @Test fun thirtyFpsKeepsTheLadder() {
        VideoQualityTier.entries.forEach { assertEquals(it.baseBitrateBps, decide(it, 30).bitrateBps) }
    }

    @Test fun sixtyFpsGetsFiftyPercentMore() {
        assertEquals(72_000_000, decide(VideoQualityTier.UHD, 60).bitrateBps)
        assertEquals(37_500_000, decide(VideoQualityTier.FHD, 60).bitrateBps)
    }

    @Test fun liveControllerStaysWithinTheSixtyFpsRung() {
        val decision = decide(VideoQualityTier.FHD, 60, SceneMotion.HIGH_MOTION)
        val controller = LiveBitrateController(decision)
        assertEquals(VideoQualityTier.FHD.bitrateFor(60), controller.ceilingBps)
        assertEquals(controller.ceilingBps, controller.candidateFor(SceneMotion.HIGH_MOTION))
        assertTrue(controller.candidateFor(SceneMotion.STATIC) < controller.ceilingBps)
    }
}
