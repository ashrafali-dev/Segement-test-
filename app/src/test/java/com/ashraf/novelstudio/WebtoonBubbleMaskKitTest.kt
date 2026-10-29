package com.ashraf.novelstudio

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
