package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun imageChapterJsonRoundTripKeepsPageOrder() {
        val original = ImageChapter(
            sourceId = "naver-comic",
            title = "Episode 67",
            novel = "Example series",
            number = "67",
            url = "https://m.comic.naver.com/webtoon/detail?titleId=12345&no=67",
            pageUrls = listOf(
                "https://cdn.example/1.jpg",
                "https://cdn.example/2.jpg",
                "https://cdn.example/3.jpg"
            )
        )

        val restored = ImageChapter.fromJson(original.toJson())

        assertEquals(original.sourceId, restored?.sourceId)
        assertEquals(original.url, restored?.url)
        assertEquals(original.pageUrls, restored?.pageUrls)
        assertNull(ImageChapter.fromJson("""{"url":"javascript:alert(1)","pages":[]}"""))
    }
}
