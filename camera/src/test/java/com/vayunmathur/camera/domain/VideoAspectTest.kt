package com.vayunmathur.camera.domain

import org.junit.Test
import kotlin.test.assertEquals

class VideoAspectTest {
    @Test
    fun tiersKnowTheirShape() {
        listOf(VideoQualityTier.UHD, VideoQualityTier.FHD, VideoQualityTier.HD, VideoQualityTier.SD)
            .forEach { assertEquals(VideoAspect.WIDE, it.aspect, "tier=$it") }
        listOf(VideoQualityTier.UHD43, VideoQualityTier.QHD43, VideoQualityTier.FHD43)
            .forEach { assertEquals(VideoAspect.STANDARD, it.aspect, "tier=$it") }
    }

    @Test
    fun stepDownStaysInTheSameShape() {
        VideoQualityTier.entries.forEach { assertEquals(it.aspect, it.lower().aspect, "tier=$it") }
        assertEquals(VideoQualityTier.QHD43, VideoQualityTier.UHD43.lower())
        assertEquals(VideoQualityTier.FHD43, VideoQualityTier.QHD43.lower())
        assertEquals(VideoQualityTier.FHD43, VideoQualityTier.FHD43.lower())
        assertEquals(VideoQualityTier.FHD, VideoQualityTier.UHD.lower())
    }

    @Test
    fun portraitBoxRatiosMatchTheLabels() {
        assertEquals(9f / 16f, VideoAspect.WIDE.portraitRatio)
        assertEquals(3f / 4f, VideoAspect.STANDARD.portraitRatio)
    }
}
