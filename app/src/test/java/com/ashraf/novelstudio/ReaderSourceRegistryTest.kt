package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSourceRegistryTest {
    private val registry = ReaderSourceRegistry()

    @Test
    fun recognizesKnownChapterUrlsButDoesNotClaimReaderViewIsReady() {
        val match = registry.find("https://www.webnovel.com/book/sample-book_123/chapter-7")

        assertNotNull(match)
        assertEquals("webnovel", match?.adapterId)
        assertEquals(ReaderSourcePageType.CHAPTER, match?.pageType)
        assertTrue(match?.capabilities?.textExtraction == true)
        assertFalse(match?.canOfferReaderView == true)
        assertNull(registry.findReaderReadyChapter("https://www.webnovel.com/book/sample-book_123/chapter-7"))
    }

    @Test
    fun recognizesSubdomainsButNotLookalikeDomains() {
        assertEquals(
            "wtr-lab",
            registry.find("https://m.wtr-lab.com/en/novel/123/title/chapter-24")?.adapterId
        )
        assertNull(registry.find("https://wtr-lab.com.example.org/en/novel/123/title/chapter-24"))
        assertNull(registry.find("https://notwebnovel.com/book/title/chapter-2"))
    }

    @Test
    fun classifiesQidianChapterUrls() {
        val match = registry.findChapter("https://www.qidian.com/chapter/123/456/")

        assertNotNull(match)
        assertEquals("qidian", match?.adapterId)
        assertEquals(ReaderSourcePageType.CHAPTER, match?.pageType)
    }

    @Test
    fun classifiesNovel543ChapterUrlsAndCatalogsSeparately() {
        assertEquals(
            ReaderSourcePageType.CHAPTER,
            registry.find("https://novel543.com/123/456.html")?.pageType
        )
        assertEquals(
            ReaderSourcePageType.SERIES,
            registry.find("https://novel543.com/123/dir")?.pageType
        )
    }

    @Test
    fun rejectsNonHttpUrlsAndMalformedInput() {
        assertNull(registry.find("javascript:alert(1)"))
        assertNull(registry.find("file:///data/local/page.html"))
        assertNull(registry.find("not a url"))
        assertNull(registry.find(null))
    }

    @Test
    fun trustedExtensionAdapterCanBeRegisteredAndRemoved() {
        val emptyRegistry = ReaderSourceRegistry(emptyList())
        val adapter = TestReaderSourceAdapter()

        assertTrue(emptyRegistry.registerAdapter(adapter))
        assertEquals("test-source", emptyRegistry.find("https://reader.example.test/chapter/9")?.adapterId)
        assertFalse(emptyRegistry.registerAdapter(adapter))
        assertTrue(emptyRegistry.removeAdapter("test-source"))
        assertNull(emptyRegistry.find("https://reader.example.test/chapter/9"))
    }

    @Test
    fun registryRejectsAnAdapterThatClaimsAnAlreadyRegisteredDomain() {
        val emptyRegistry = ReaderSourceRegistry(emptyList())
        assertTrue(emptyRegistry.registerAdapter(TestReaderSourceAdapter()))
        assertFalse(emptyRegistry.registerAdapter(TestReaderSourceAdapter(id = "second-source")))
    }

    private class TestReaderSourceAdapter(
        override val id: String = "test-source"
    ) : ReaderSourceAdapter {
        override val displayName: String = "Test Source"
        override val domains: Set<String> = setOf("reader.example.test")
        override val contentKind: ReaderContentKind = ReaderContentKind.IMAGE_PAGES
        override val capabilities: ReaderSourceCapabilities = ReaderSourceCapabilities(
            imagePageExtraction = true,
            nativeReaderReady = true
        )

        override fun classify(url: ReaderSourceUrl): ReaderSourcePageType =
            if (url.pathSegments.firstOrNull() == "chapter") {
                ReaderSourcePageType.CHAPTER
            } else {
                ReaderSourcePageType.OTHER
            }
    }
}
