package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals

class IsoSplitTest {
    @Test
    fun withinSensorRangeHasNoBoost() = assertEquals(3200 to 100, IsoSplit.split(3200, 6400, 800))

    @Test
    fun aboveSensorRangeUsesBoost() = assertEquals(6400 to 400, IsoSplit.split(25600, 6400, 800))

    @Test
    fun boostIsClamped() = assertEquals(6400 to 200, IsoSplit.split(25600, 6400, 200))

    @Test
    fun maxIsoMultipliesBoost() = assertEquals(25600, IsoSplit.maxIso(6400, 400))

    @Test
    fun missingBoostKeepsSensorMax() = assertEquals(6400, IsoSplit.maxIso(6400, 100))
}
