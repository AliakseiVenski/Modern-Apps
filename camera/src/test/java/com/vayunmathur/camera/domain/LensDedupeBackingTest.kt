package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** [LensLabels.dedupe] on logical multi-camera lenses that share physical sensors. */
class LensDedupeBackingTest {
    private fun lens(
        id: String,
        type: LensType,
        backing: Set<String>?,
        eq35Mm: Float? = null,
        ratio: Float = 1f,
    ) = PhysicalLens(
        logicalCameraId = id,
        lensType = type,
        facing = LensFacing.BACK,
        focalLengthMm35Eq = eq35Mm,
        nominalZoomRatio = ratio,
        physicalBacking = backing,
        fallbackPriority = 0,
        labelKey = type.name.lowercase(),
    )

    @Test
    fun logicalLensesOverTheSamePhysicalSensorsCollapseToTheFirst() {
        val stops = listOf(
            lens("0", LensType.WIDE, setOf("2", "3", "4"), eq35Mm = 27f),
            lens("5", LensType.WIDE, setOf("4", "3", "2"), eq35Mm = 54f, ratio = 2f),
        )
        assertEquals(listOf("0"), LensLabels.dedupe(stops).map { it.logicalCameraId })
    }

    @Test
    fun differentPhysicalBackingKeepsBothLenses() {
        val lenses = listOf(
            lens("0", LensType.WIDE, setOf("2", "3"), eq35Mm = 27f),
            lens("5", LensType.WIDE, setOf("6", "7"), eq35Mm = 54f, ratio = 2f),
        )
        assertEquals(2, LensLabels.dedupe(lenses).size)
    }

    @Test
    fun lensesWithoutBackingStillDedupeByCharacteristics() {
        val lenses = listOf(
            lens("1", LensType.ULTRA_WIDE, null, eq35Mm = 13.5f, ratio = 0.5f),
            lens("2", LensType.ULTRA_WIDE, null, eq35Mm = 13.5f, ratio = 0.5f),
            lens("0", LensType.WIDE, null, eq35Mm = 27f),
        )
        assertEquals(listOf("1", "0"), LensLabels.dedupe(lenses).map { it.logicalCameraId })
    }
}
