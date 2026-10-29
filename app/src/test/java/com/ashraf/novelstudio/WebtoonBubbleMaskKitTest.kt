package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonBubbleMaskKitTest {
    @Test
    fun toongoodWebtoonPathUsesDedicatedKit() {
        assertTrue(WebtoonBubbleMaskKit.shouldHandle(
            "https://www.toongood.org/webtoon/the-heavenly-demon-cant-live-a-normal-life"
        ))
    }

    @Test
    fun overlaySafetyMaskSurvivesLayoutCacheRoundTrip() {
        val cover = listOf(0.31f, 0.42f, 0.69f, 0.48f)
        val bubble = WmBubble(
            src = "https://www.toongood.org/webtoon/example",
            cx = 0.5f, cy = 0.45f, w = 0.6f, h = 0.2f,
            angle = 0f, font = 0.03f, bg = 0xFFFFFFFF.toInt(), fg = 0xFF000000.toInt(),
            sourceText = "HELLO", overlayCv = cover
        )
        val stored = WebModeLayout.fromJson(WebModeLayout(0, listOf(bubble)).toJson())!!
        assertEquals(cover, stored.bubbles.single().overlayCv)
    }

    @Test
    fun webtoonUsesFinerBandsAndMangaKeepsDefault() {
        assertTrue(WebtoonBubbleMaskKit.shapeBandLimit(
            "https://www.toongood.org/webtoon/the-heavenly-demon"
        ) == 96)
        assertTrue(WebtoonBubbleMaskKit.shapeBandLimit(
            "https://example.com/manga/chapter-12"
        ) == 28)
        assertTrue(WebtoonBubbleMaskKit.segmentationBandLimit(
            "https://www.toongood.org/webtoon/the-heavenly-demon"
        ) == 64)
        assertTrue(WebtoonBubbleMaskKit.segmentationBandLimit(
            "https://example.com/manga/chapter-12"
        ) == 14)
    }

    @Test
    fun webtoonsHostUsesDedicatedKit() {
        assertTrue(WebtoonBubbleMaskKit.shouldHandle("https://www.webtoons.com/en/action/example/list"))
    }

    @Test
    fun japaneseMangaRoutesDoNotUseWebtoonKit() {
        assertFalse(WebtoonBubbleMaskKit.shouldHandle("https://example.com/manga/chapter-12"))
        assertFalse(WebtoonBubbleMaskKit.shouldHandle("https://mangareader.example/comic/title/12"))
    }

    @Test
    fun mentionInsideQueryDoesNotEnableWebtoonKit() {
        assertFalse(WebtoonBubbleMaskKit.shouldHandle("https://example.com/manga/title?next=/webtoon/example"))
    }

    @Test
    fun emptyOrMalformedUrlDoesNotEnableWebtoonKit() {
        assertFalse(WebtoonBubbleMaskKit.shouldHandle(""))
        assertFalse(WebtoonBubbleMaskKit.shouldHandle("not a URL"))
    }
}
