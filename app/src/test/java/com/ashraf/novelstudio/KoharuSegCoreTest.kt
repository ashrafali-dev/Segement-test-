package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoharuSegCoreTest {
    private val size = 512
    private val protoSize = size / 4
    private val letterbox = KoharuSegCore.Letterbox(1f, 1f, 0, 0, size, size)
    private val tile = intArrayOf(0, 0, size, size)

    private fun row(
        output: FloatArray,
        index: Int,
        box: FloatArray,
        score: Float,
        classId: Int
    ) {
        val p = index * KoharuSegCore.ROW_SIZE
        for (i in 0..3) output[p + i] = box[i]
        output[p + 4] = score
        output[p + 5] = classId.toFloat()
        // First coefficient selects the positive synthetic prototype below.
        output[p + 6] = 1f
    }

    @Test fun mapsAllFourKoharuClassesIntoTheExistingDetectorContract() {
        val pred = FloatArray(5 * KoharuSegCore.ROW_SIZE)
        val proto = FloatArray(KoharuSegCore.PROTO_CHANNELS * protoSize * protoSize)
        for (i in 0 until protoSize * protoSize) proto[i] = 1f

        row(pred, 0, floatArrayOf(0f, 0f, 512f, 512f), .99f, 0) // frame: ignored
        row(pred, 1, floatArrayOf(50f, 60f, 250f, 300f), .95f, 2) // balloon
        row(pred, 2, floatArrayOf(85f, 100f, 200f, 140f), .91f, 1) // dialogue_text
        row(pred, 3, floatArrayOf(300f, 50f, 400f, 100f), .88f, 3) // onomatopoeia_text
        row(pred, 4, floatArrayOf(52f, 62f, 248f, 298f), .75f, 2) // duplicate balloon

        val found = KoharuSegCore.decode(pred, proto, protoSize, protoSize, size, tile, letterbox)

        assertEquals(3, found.size)
        assertEquals(
            setOf(
                BubbleDetectorCore.CLS_BUBBLE,
                BubbleDetectorCore.CLS_TEXT_BUBBLE,
                BubbleDetectorCore.CLS_TEXT_FREE
            ),
            found.map { it.label }.toSet()
        )
        assertTrue(found.all { it.mask != null })
        assertTrue(found.all { it.score >= KoharuSegCore.SCORE_MIN })
        assertFalse(found.any { it.label == 0 && it.l == 0 && it.t == 0 && it.r == 512 && it.b == 512 })
    }

    @Test fun dynamicInputSizesMustRespectTheModelStride() {
        assertTrue(KoharuSegCore.validInputSize(448))
        assertTrue(KoharuSegCore.validInputSize(512))
        assertTrue(KoharuSegCore.validInputSize(640))
        assertTrue(KoharuSegCore.validInputSize(768))
        assertFalse(KoharuSegCore.validInputSize(500))
        assertFalse(KoharuSegCore.validInputSize(2048))
    }

    @Test fun rejectsOutputWithWrongRowContract() {
        val badRows = FloatArray(37)
        val proto = FloatArray(KoharuSegCore.PROTO_CHANNELS * protoSize * protoSize)
        try {
            KoharuSegCore.decode(badRows, proto, protoSize, protoSize, size, tile, letterbox)
            throw AssertionError("expected the wrong output-row shape to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("too small") ||
                expected.message.orEmpty().contains("38"))
        }
    }
}
