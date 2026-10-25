package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageChapterAdapterTest {
    private val adapter = NaverComicImageAdapter()

    @Test
    fun recognizesOnlyNaverEpisodeDetailUrls() {
        assertTrue(adapter.matches("https://m.comic.naver.com/webtoon/detail?titleId=12345&no=67"))
        assertTrue(adapter.matches("https://comic.naver.com/webtoon/detail?titleId=12345&no=67"))
        assertTrue(adapter.matches("https://m.comic.naver.com/bestChallenge/detail?titleId=12345&no=7"))

        assertFalse(adapter.matches("https://m.comic.naver.com/webtoon/list?titleId=12345"))
        assertFalse(adapter.matches("https://m.comic.naver.com/webtoon/detail?titleId=abc&no=67"))
        assertFalse(adapter.matches("https://example.org/webtoon/detail?titleId=12345&no=67"))
        assertFalse(adapter.matches("javascript:alert(1)"))
        assertFalse(adapter.matches(null))
    }


    @Test
    fun prefersMihonDesktopViewerPagesInSourceOrder() {
        val pages = NaverComicPageSources.prefer(
            listOf("https://cdn.example/p2.jpg", "https://cdn.example/p1.jpg", "https://cdn.example/p2.jpg#duplicate"),
            listOf("https://cdn.example/mobile-p1.jpg"),
            listOf("https://cdn.example/fallback.jpg")
        )
        assertEquals(listOf("https://cdn.example/p2.jpg", "https://cdn.example/p1.jpg"), pages)
    }

    @Test
    fun usesMihonMobileDataSrcPagesWhenDesktopViewerIsEmpty() {
        val pages = NaverComicPageSources.prefer(
            emptyList(),
            listOf("https://cdn.example/mobile-1.jpg", "https://cdn.example/mobile-2.jpg"),
            listOf("https://cdn.example/fallback.jpg")
        )
        assertEquals(listOf("https://cdn.example/mobile-1.jpg", "https://cdn.example/mobile-2.jpg"), pages)
    }

    @Test
    fun usesKnownNaverFallbackOnlyWhenMihonContainersAreEmpty() {
        val pages = NaverComicPageSources.prefer(
            emptyList(),
            emptyList(),
            listOf("https://cdn.example/fallback-1.jpg", "https://cdn.example/fallback-2.jpg")
        )
        assertEquals(listOf("https://cdn.example/fallback-1.jpg", "https://cdn.example/fallback-2.jpg"), pages)
    }

    @Test
    fun selectorPriorityDoesNotDropSmallButValidPageUrls() {
        val smallPanel = "https://cdn.example/panel-small.webp"
        assertEquals(listOf(smallPanel), NaverComicPageSources.prefer(listOf(smallPanel), emptyList()))
    }

    @Test
    fun imageUrlsAreValidatedDeduplicatedAndKeepSourceOrder() {
        val urls = ImageChapterPageUrls.orderedDistinct(
            listOf(
                " https://cdn.example/page-1.jpg#top ",
                "https://cdn.example/page-2.jpg?token=a%2Fb",
                "https://cdn.example/page-1.jpg#bottom",
                "data:image/png;base64,abc",
                "file:///tmp/page-3.jpg",
                "https://user:pass@cdn.example/page-4.jpg",
                "https://cdn.example/page-3.jpg"
            )
        )

        assertEquals(
            listOf(
                "https://cdn.example/page-1.jpg",
                "https://cdn.example/page-2.jpg?token=a%2Fb",
                "https://cdn.example/page-3.jpg"
            ),
            urls
        )
    }
}
