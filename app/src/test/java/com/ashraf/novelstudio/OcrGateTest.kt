package com.ashraf.novelstudio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic (latch-driven, no sleeps for ordering) tests of OcrGate's foreground-over-background rule. */
class OcrGateTest {
    private fun bg(block: () -> Unit) = Thread { OcrGate.markBackgroundThread(); block() }.apply { isDaemon = true }

    @Test fun foregroundWaitsForAtMostTheRunningBackgroundCall_andQueuedBackgroundYields() {
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val bg1Running = CountDownLatch(1)
        val releaseBg1 = CountDownLatch(1)
        val fgQueued = CountDownLatch(1)

        val t1 = bg { OcrGate.run { order.add("bg1-start"); bg1Running.countDown(); releaseBg1.await(5, TimeUnit.SECONDS); order.add("bg1-end") } }
        t1.start()
        assertTrue(bg1Running.await(5, TimeUnit.SECONDS))

        val fg = Thread { fgQueued.countDown(); OcrGate.run { order.add("fg") } }.apply { isDaemon = true }
        fg.start()
        assertTrue(fgQueued.await(5, TimeUnit.SECONDS))
        // wait until the foreground has really registered as pending
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!OcrGate.foregroundPending() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(OcrGate.foregroundPending())

        val t2 = bg { OcrGate.run { order.add("bg2") } }   // a second read-ahead job arrives while fg is pending
        t2.start()

        releaseBg1.countDown()
        fg.join(5000); t2.join(5000); t1.join(5000)
        assertEquals(listOf("bg1-start", "bg1-end", "fg", "bg2"), order.toList())
    }

    @Test fun foregroundRunsImmediatelyWhenIdle() {
        val n = AtomicInteger()
        OcrGate.run { n.incrementAndGet() }
        assertEquals(1, n.get())
        assertTrue(!OcrGate.foregroundPending())
    }

    @Test fun gateIsReleasedWhenBlockThrows() {
        try { OcrGate.run<Unit> { throw IllegalStateException("x") } } catch (_: IllegalStateException) {}
        assertEquals(7, OcrGate.run { 7 })
        assertTrue(!OcrGate.foregroundPending())
    }
}
