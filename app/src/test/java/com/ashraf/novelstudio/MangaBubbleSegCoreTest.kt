package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaBubbleSegCoreTest {
    @Test fun decodesChannelFirstYoloSegAndUsesTheInstanceMaskForContainment() {
        val s = MangaBubbleSegCore
        val pred = FloatArray(s.CHANNELS * s.ANCHORS)
        val proto = FloatArray(s.PROTO_CHANNELS * s.PROTO_SIZE * s.PROTO_SIZE)
        val anchor = 13
        pred[anchor] = 512f
        pred[s.ANCHORS + anchor] = 512f
        pred[2 * s.ANCHORS + anchor] = 400f
        pred[3 * s.ANCHORS + anchor] = 300f
        pred[4 * s.ANCHORS + anchor] = 0.95f
        pred[5 * s.ANCHORS + anchor] = 1f
        for (y in 100 until 156) for (x in 110 until 151) {
            proto[y * s.PROTO_SIZE + x] = 2f
        }
        val lb = MangaBubbleSegCore.Letterbox(1f, 1f, 0, 0, s.SIZE, s.SIZE)
        val detections = s.decode(pred, proto, intArrayOf(0, 0, s.SIZE, s.SIZE), lb)
        assertEquals(1, detections.size)
        val d = detections.single()
        assertEquals(0, d.label)
        assertEquals(listOf(312, 362, 712, 662), listOf(d.l, d.t, d.r, d.b))
        assertTrue(d.contains(512f, 512f))
        assertFalse(d.contains(330f, 380f))

        // The mask-backed model already classifies this as a balloon; a very short text line must not trip
        // the old box-only size-ratio guard that was intended to reject panel-sized false positives.
        val largeMaskedBalloon = BubbleDetectorCore.Det(
            0, 0.95f, 0, 0, 1000, 1000,
            BubbleDetectorCore.Mask(64, 64, ByteArray(64 * 64) { 1 })
        )
        assertTrue(BubbleDetectorCore.interiorFor(listOf(largeMaskedBalloon), 490, 490, 510, 510) != null)
    }

    @Test fun removesPredictionsWithLowScoreAndRejectsInvalidBoxes() {
        val s = MangaBubbleSegCore
        val pred = FloatArray(s.CHANNELS * s.ANCHORS)
        val proto = FloatArray(s.PROTO_CHANNELS * s.PROTO_SIZE * s.PROTO_SIZE)
        val lb = MangaBubbleSegCore.Letterbox(1f, 1f, 0, 0, s.SIZE, s.SIZE)
        pred[0] = Float.NaN
        pred[4 * s.ANCHORS] = 0.1f
        assertTrue(s.decode(pred, proto, intArrayOf(0, 0, s.SIZE, s.SIZE), lb).isEmpty())
    }
}
