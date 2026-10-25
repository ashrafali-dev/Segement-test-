package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression test for sound-effect cleanup used during overlay health checks. */
class WmSfxTest {
    @Test
    fun collapsesLongRepeatedRunsWithoutChangingMappedSoundEffects() {
        val wasBengali = WmSfx.bengali
        try {
            WmSfx.bengali = true
            assertEquals("ধুম!", WmSfx.fix("BOOOOM"))
            assertEquals("ধুম!", WmSfx.fix("BOOM"))
        } finally {
            WmSfx.bengali = wasBengali
        }
    }
}
