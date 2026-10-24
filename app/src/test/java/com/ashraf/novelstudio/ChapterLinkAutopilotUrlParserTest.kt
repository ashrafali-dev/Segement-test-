package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterLinkAutopilotUrlParserTest {
    @Test fun extractsLinksAndTrimsPunctuation() {
        assertEquals(
            listOf("https://reader.example/manga/a/chapter-1","https://reader.example/manga/a/chapter-2"),
            ChapterLinkAutopilotUrlParser.extract("Chapter 1: https://reader.example/manga/a/chapter-1, https://reader.example/manga/a/chapter-2.\nhttps://reader.example/manga/a/chapter-1")
        )
    }
    @Test fun acceptsOnlyHttpSchemes() {
        assertTrue(ChapterLinkAutopilotUrlParser.isHttpUrl("https://reader.example/chapter/1"))
        assertTrue(ChapterLinkAutopilotUrlParser.isHttpUrl("http://reader.example/chapter/1"))
        assertTrue(!ChapterLinkAutopilotUrlParser.isHttpUrl("file:///data/local/tmp/chapter.html"))
        assertTrue(!ChapterLinkAutopilotUrlParser.isHttpUrl("javascript:alert(1)"))
    }
}
