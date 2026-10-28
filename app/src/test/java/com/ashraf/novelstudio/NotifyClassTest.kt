package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyClassTest {

    @Test
    fun failuresAndLoginBecomeBanners() {
        assertEquals(NotifyClass.Kind.BANNER, NotifyClass.classify("Could not read the answer"))
        assertEquals(NotifyClass.Kind.BANNER, NotifyClass.classify("❌ Translation failed"))
        assertEquals(NotifyClass.Kind.BANNER, NotifyClass.classify("ChatGPT needs you to log in"))
        assertEquals(NotifyClass.Kind.BANNER, NotifyClass.classify("CAPTCHA required"))
        assertEquals(NotifyClass.Kind.BANNER, NotifyClass.classify("Next chapter link not found"))
        assertEquals(NotifyClass.Kind.BANNER, NotifyClass.classify("Previous OCR still running"))
    }

    @Test
    fun confirmationsAndInfoGoToTheLog() {
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("Link copied"))
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("✅ Saved"))
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("Translation stopped"))
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("Learned the Next button of wtr-lab.com"))
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("Report copied"))
    }

    @Test
    fun aScaryWordInsideASuccessIsStillASuccess() {
        // "saved" inside a short positive sentence must not become an amber banner.
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("Bookmark saved"))
    }

    @Test
    fun cleanStripsEmojiButKeepsText() {
        assertEquals("Background translation stopped", NotifyClass.clean("⏹ Background translation stopped"))
        assertEquals("Reloading both pages", NotifyClass.clean("🔄 Reloading both pages"))
        assertEquals("Chapter 12 saved", NotifyClass.clean("✅ Chapter 12 saved ✨"))
    }

    @Test
    fun emptyMessageIsLogAndWouldBeDroppedByTheCaller() {
        assertEquals(NotifyClass.Kind.LOG, NotifyClass.classify("   "))
    }
}
