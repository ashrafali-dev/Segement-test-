package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusModelTest {

    private fun build(
        statuses: List<Pair<String, String>> = emptyList(),
        chatFound: Int = 0,
        chatTotal: Int = 0,
        chatPhase: Int = 0,
        queueSize: Int = 0,
        aheadOn: Boolean = false,
        aheadReady: Int = 0,
        attention: String? = null
    ) = StatusModel().build(
        statuses = statuses,
        chatFound = chatFound,
        chatTotal = chatTotal,
        chatPhase = chatPhase,
        chatElapsedSec = 12,
        chatProvider = "ChatGPT",
        queueSize = queueSize,
        aheadOn = aheadOn,
        aheadReady = aheadReady,
        attentionText = attention,
        detailLine = "OCR Lens · Detector on · AI painter on"
    )

    @Test
    fun determinateChatProgressIsExposedForTheRing() {
        val s = build(listOf("chatbot-translation" to "x"), chatFound = 42, chatTotal = 120, chatPhase = 2)
        assertTrue(s.chatHasProgress)
        assertEquals(42, s.chatCount)
        assertEquals(120, s.chatTotal)
        val lane = s.chatLane
        assertNotNull(lane)
        assertEquals(42, lane!!.n)
        assertEquals(120, lane.total)
        assertEquals(0.35f, lane.fraction, 0.001f)
        assertTrue(s.anyWork)
    }

    @Test
    fun unknownTotalNeverFakesProgress() {
        val s = build(listOf("chatbot-translation" to "x"), chatFound = 0, chatTotal = 0, chatPhase = 2)
        assertFalse(s.chatHasProgress)
        assertEquals(-1, s.chatCount)
        assertNull(s.chatLane!!.n)
        assertFalse(s.chatLane!!.hasProgress)
    }

    @Test
    fun attentionOutranksEverything() {
        val s = build(
            listOf("chatbot-translation" to "x"), chatFound = 1, chatTotal = 4, chatPhase = 2,
            attention = "ChatGPT needs you to log in"
        )
        assertTrue(s.needsAttention)
        assertEquals("ChatGPT needs you to log in", s.attentionText)
    }

    @Test
    fun queueAndAheadLanesAppearOnlyWhenRelevant() {
        assertTrue(build(queueSize = 0).lanes.none { it.key == "queue" })
        val q = build(queueSize = 2)
        assertEquals("2", q.lanes.first { it.key == "queue" }.rightText)
        // Ahead lane only when the feature is on and something is ready.
        assertTrue(build(aheadOn = false, aheadReady = 5).lanes.none { it.key == "ahead-ready" })
        assertEquals("5 ready", build(aheadOn = true, aheadReady = 5).lanes.first { it.key == "ahead-ready" }.rightText)
    }

    @Test
    fun idleSnapshotHasNoWork() {
        val s = build()
        assertFalse(s.anyWork)
        assertFalse(s.needsAttention)
        assertNull(s.chatLane)
        assertEquals("OCR Lens · Detector on · AI painter on", s.detailLine)
    }

    @Test
    fun readLanesAreReportedWithTheirStateText() {
        val s = build(listOf("visible-ocr" to "Reading this screen · ML Kit"))
        val lane = s.lanes.first { it.key == "read" }
        assertEquals("Reading this screen · ML Kit", lane.stateText)
        assertEquals(StatusModel.State.WORKING, lane.state)
    }
}
