package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSourceUrlTest {
    @Test
    fun acceptsHttpAndHttpsUrlsWithoutChangingChapterIdentity() {
        val queryUrl = "https://example.com/book/chapter-12?id=42#page-3"
        assertEquals(queryUrl, ReaderSourceUrl.validate(queryUrl))
        assertEquals("http://example.com/chapter/12", ReaderSourceUrl.validate("http://example.com/chapter/12"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("https://example.com/chapter/12", ReaderSourceUrl.validate("  https://example.com/chapter/12  "))
    }

    @Test
    fun rejectsNonWebAndMalformedUrls() {
        assertNull(ReaderSourceUrl.validate(null))
        assertNull(ReaderSourceUrl.validate(""))
        assertNull(ReaderSourceUrl.validate("javascript:alert(1)"))
        assertNull(ReaderSourceUrl.validate("file:///etc/passwd"))
        assertNull(ReaderSourceUrl.validate("https:///missing-host"))
        assertNull(ReaderSourceUrl.validate("https://user:password@example.com/chapter/1"))
    }

    @Test
    fun hostIsNormalizedForSourceMatching() {
        assertEquals("example.com", ReaderSourceUrl.host("https://www.Example.com/chapter/1"))
        assertNull(ReaderSourceUrl.host("data:text/plain,hello"))
    }
}
