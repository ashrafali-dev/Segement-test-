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

class DropChromeImagesTest {
    private fun p(src: String, top: Int, w: Int, h: Int) = PanelRef(src, top, 0, w, h)

    @Test fun bannersAvatarsAndPromoTilesFromOtherHostsAreDropped() {
        val refs = listOf(
            p("https://image-comic.pstatic.net/a1", 27300, 420, 975),
            p("https://image-comic.pstatic.net/a2", 28275, 420, 975),
            p("https://image-comic.pstatic.net/a3", 40950, 420, 86),
            p("https://ssl.pstatic.net/melona/x", 41066, 420, 420),
            p("https://kw-wcommunity-phinf.pstatic.net/u", 41536, 34, 34))
        val kept = WebModeLogic.dropChromeImages(refs).map { it.src }
        assertEquals(listOf("https://image-comic.pstatic.net/a1", "https://image-comic.pstatic.net/a2", "https://image-comic.pstatic.net/a3"), kept)
    }

    @Test fun panelsOfAMixedCdnChapterAreKept() {
        val refs = listOf(p("https://a.example/1", 0, 800, 1200), p("https://b.example/2", 1200, 800, 1200))
        assertEquals(2, WebModeLogic.dropChromeImages(refs).size)
    }

    @Test fun singleHostIsUntouched() {
        val refs = listOf(p("https://a.example/1", 0, 800, 1200), p("https://a.example/2", 1200, 800, 90))
        assertEquals(2, WebModeLogic.dropChromeImages(refs).size)
    }
}


class DedupeAndOrderTest {
    @Test fun sameImageUrlAtDifferentDocumentOffsetsRemainsDistinct() {
        val url = "https://cdn.example.test/panel.jpg"
        val kept = WebModeLogic.dedupeAndOrder(listOf(
            PanelRef(url, 0, 0, 420, 597),
            PanelRef(url, 600, 0, 420, 597),
            // The CDN also exposed a clone of the first panel with a slightly different URL.
            PanelRef(url + "?clone=1", 1, 1, 420, 597)
        ))
        assertEquals(2, kept.size)
        assertEquals(listOf(0, 600), kept.map { it.top })
    }

    @Test fun sourceUrlIsNotUsedToKeepDuplicateDomClones() {
        val kept = WebModeLogic.dedupeAndOrder(listOf(
            PanelRef("https://cdn.example.test/panel.jpg", 100, 20, 420, 597),
            PanelRef("https://cdn.example.test/panel.jpg?cache=2", 101, 21, 420, 598)
        ))
        assertEquals(1, kept.size)
    }
}
