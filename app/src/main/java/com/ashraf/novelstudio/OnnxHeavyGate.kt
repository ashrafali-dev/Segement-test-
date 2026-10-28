package com.ashraf.novelstudio

import java.util.concurrent.locks.ReentrantLock

/**
 * One lock for every heavy on-device ONNX job (bubble detector, AI painter / LaMa).
 * Before this each had its own monitor, so on a phone the detector (read-ahead thread) and the painter
 * (visible thread) could run - and allocate native memory - at the same time. On a 32-bit / 3.5 GB phone that
 * double allocation is what killed the whole process (a native crash cannot be caught by try/catch).
 * Lock order is always: OnnxHeavyGate.lock -> the engine's own monitor.
 */
internal object OnnxHeavyGate {
    val lock = ReentrantLock(true)
}
