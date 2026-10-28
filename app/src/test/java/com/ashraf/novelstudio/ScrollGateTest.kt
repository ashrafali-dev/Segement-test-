package com.ashraf.novelstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollGateTest {
    private val vh = 2000

    @Test fun noEventsMeansNoHold() {
        ScrollGate.reset()
        assertFalse(ScrollGate.holdOcr(0, vh))
    }

    @Test fun slowReadingScrollNeverHoldsOcr() {
        ScrollGate.reset()
        var t = 1000L; var y = 0
        repeat(20) { ScrollGate.onScroll(t, y, vh); t += 16; y += 5 }
        assertFalse(ScrollGate.holdOcr(t, vh))
    }

    @Test fun fastFlickHoldsThenReleasesAfterCalmPeriod() {
        ScrollGate.reset()
        var t = 5000L; var y = 0
        repeat(10) { ScrollGate.onScroll(t, y, vh); t += 16; y += 96 }
        assertTrue(ScrollGate.holdOcr(t, vh))
        assertTrue(ScrollGate.holdOcr(t + 200, vh))
        assertFalse(ScrollGate.holdOcr(t + 400, vh))
    }

    @Test fun ocrRunsAgainWhenTheFlickSlowsDown() {
        ScrollGate.reset()
        var t = 5000L; var y = 0
        repeat(10) { ScrollGate.onScroll(t, y, vh); t += 16; y += 96 }
        repeat(30) { ScrollGate.onScroll(t, y, vh); t += 16; y += 4 }
        assertFalse(ScrollGate.holdOcr(t + 300, vh))
    }

    @Test fun zeroViewportIsSafe() {
        ScrollGate.reset()
        ScrollGate.onScroll(0, 0, 0); ScrollGate.onScroll(50, 1000, 0)
        assertFalse(ScrollGate.holdOcr(60, 0))
    }
}
