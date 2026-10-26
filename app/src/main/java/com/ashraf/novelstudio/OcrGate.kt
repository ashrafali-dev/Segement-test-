package com.ashraf.novelstudio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Single priority gate for every OCR inference.
 *
 * Before this, the visible-panel job (thread "ns-webmode") and the read-ahead job ("ns-webmode-ahead")
 * both called the same engine; the local engine's run() was synchronized, so a visible scan queued behind
 * whatever read-ahead inference happened to be running AND behind any further read-ahead slices that
 * grabbed the monitor first. An in-flight ONNX/ML Kit call cannot be interrupted, so the best we can
 * guarantee is: the foreground waits for at most ONE in-flight inference, and no new background
 * inference starts while a foreground one is waiting or running.
 */
internal object OcrGate {
    private val lock = ReentrantLock(true)          // fair: FIFO among waiters
    private val foregroundActive = AtomicInteger(0)  // waiting or running foreground inferences
    private val background = ThreadLocal<Boolean>()

    /** Mark the current thread as a read-ahead thread (call once at the top of its Runnable). */
    fun markBackgroundThread() { background.set(true) }

    /** True while at least one foreground inference is waiting or running (read-ahead should not start). */
    fun foregroundPending(): Boolean = foregroundActive.get() > 0

    private fun isBackground(): Boolean = background.get() == true

    fun isBackgroundThread(): Boolean = isBackground()

    /**
     * Lens OCR is network-bound and its client is safe for overlapping requests. Once the Lens
     * engine is already active, let foreground OCR run beside an in-flight read-ahead Lens request
     * instead of waiting for that remote round trip. Incrementing foregroundActive pauses any NEW
     * background request while the visible one is pending/running. Local model inference still uses run().
     */
    fun <T> runLensForeground(waited: Waited? = null, block: () -> T): T {
        if (isBackground()) return run(waited, block)
        val t0 = System.nanoTime() / 1_000_000L
        foregroundActive.incrementAndGet()
        waited?.ms = (System.nanoTime() / 1_000_000L) - t0
        try {
            return block()
        } finally {
            foregroundActive.decrementAndGet()
        }
    }

    /** Milliseconds the last call spent waiting for the gate (diagnostics only). */
    class Waited(var ms: Long = 0L)

    fun <T> run(waited: Waited? = null, block: () -> T): T = run(waited, timeoutMs = 0L, block)

    /**
     * [timeoutMs] > 0 bounds how long a FOREGROUND caller waits for the gate (0 = wait forever, the old
     * behaviour). A bounded foreground request stops the "concurrentLens=false, gateWaitMs=300–600 ms" spikes
     * from turning into unbounded stalls behind a local inference: on timeout it runs anyway, because a Lens
     * request is network-bound and never touches the local engine the lock protects.
     */
    fun <T> run(waited: Waited? = null, timeoutMs: Long = 0L, block: () -> T): T {
        val t0 = (System.nanoTime() / 1_000_000L)
        var locked = false
        if (isBackground()) {
            // Yield to visible work; poll cheaply, and stay interruptible so shutdownNow() works.
            while (foregroundActive.get() > 0) Thread.sleep(20)
            lock.lockInterruptibly()
            locked = true
            // A foreground request may have arrived while we queued: let it go first.
            while (foregroundActive.get() > 0) {
                lock.unlock()
                locked = false
                Thread.sleep(20)
                lock.lockInterruptibly()
                locked = true
            }
        } else {
            foregroundActive.incrementAndGet()
            try {
                locked = if (timeoutMs > 0L) lock.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                else { lock.lock(); true }
            } catch (t: Throwable) { foregroundActive.decrementAndGet(); throw t }
        }
        waited?.ms = (System.nanoTime() / 1_000_000L) - t0
        try {
            return block()
        } finally {
            if (locked) lock.unlock()
            if (!isBackground()) foregroundActive.decrementAndGet()
        }
    }
}
