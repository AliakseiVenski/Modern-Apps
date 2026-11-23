package com.vayunmathur.camera.platform

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordingCoreTakeConfigTest {
    private val prepared = CustomRecordingConfig(width = 3840, height = 2160, fps = 60)

    @Test
    fun takeFieldsReplaceThePreparedOnes() {
        val core = Camera2RecordingCore(prepared, initialBitrateBps = { 1 })
        core.applyTakeConfig(prepared.copy(audioEnabled = false, orientationDegrees = 90))
        assertEquals(90, core.config.orientationDegrees)
        assertEquals(false, core.config.audioEnabled)
    }

    @Test
    fun aDifferentVideoFormatIsRejected() {
        val core = Camera2RecordingCore(prepared, initialBitrateBps = { 1 })
        assertFailsWith<IllegalArgumentException> { core.applyTakeConfig(prepared.copy(fps = 30)) }
        assertFailsWith<IllegalArgumentException> { core.applyTakeConfig(prepared.copy(hdr = true)) }
    }
}
