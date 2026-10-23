package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebModeLogicTest {
    @Test
    fun realtimeFallbackPrefersMangaScriptsForGenericLatinGuess() {
        assertEquals(
            listOf(OcrScript.JAPANESE, OcrScript.KOREAN, OcrScript.CHINESE),
            WebModeLogic.realtimeFallbackScripts(OcrScript.LATIN)
        )
    }

    @Test
    fun realtimeFallbackIncludesEveryOtherScriptExactlyOnce() {
        for (script in OcrScript.values()) {
            val fallbacks = WebModeLogic.realtimeFallbackScripts(script)
            assertEquals(OcrScript.values().size - 1, fallbacks.size)
            assertFalse("Fallback must not retry the same script: $script", fallbacks.contains(script))
            assertEquals(OcrScript.values().size - 1, fallbacks.toSet().size)
        }
    }
}
