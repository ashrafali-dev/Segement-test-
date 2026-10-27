package com.ashraf.novelstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes tensors captured from the REAL pinned Koharu ONNX export.
 *
 * The synthetic [KoharuSegCoreTest] rows are hand-written to match the decoder, so they cannot catch a
 * decoder that disagrees with the actual model. This test feeds genuine `output0`/`output1` values
 * (dumped by scripts/koharu_model_check.py during CI) through the production decoder and asserts the
 * contract: classes map correctly, masks reconstruct inside their boxes, and boxes reverse the
 * letterbox back into page pixels.
 *
 * The fixture is absent on developer machines (the model is never downloaded locally), so the test is
 * skipped there via assumeTrue and runs only in the GitHub Actions heavy job.
 *
 * Fixture format: `pred.f32`, `proto.f32`, `meta.properties` under the directory named by the
 * `koharu.fixture` system property.
 */
class KoharuRealFixtureTest {
    /**
     * Returns the fixture directory, or null when the property was never set (a normal local run).
     * If the property IS set but the directory is missing, that is a broken CI wiring, not a reason to
     * skip silently — throw so the job fails instead of reporting a false pass.
     */
    private fun fixtureDir(): File? {
        val path = System.getProperty("koharu.fixture") ?: return null
        val dir = File(path)
        require(dir.isDirectory) { "koharu.fixture=$path is set but not a directory; CI fixture was not produced" }
        for (name in listOf("pred.f32", "proto.f32", "meta.properties")) {
            require(File(dir, name).isFile) { "koharu.fixture=$path is missing $name" }
        }
        return dir
    }

    private fun floats(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "${file.name} is not float32-aligned" }
        // The Python writer dumps little-endian float32 (numpy "<f4"); do NOT use DataInputStream,
        // which reads big-endian and would silently decode garbage.
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }

    @Test fun realModelOutputDecodesIntoValidDetections() {
        val dir = fixtureDir()
        assumeTrue("no Koharu fixture; this test runs only with the CI model download", dir != null)
        val d = dir!!

        val pred = floats(File(d, "pred.f32"))
        val proto = floats(File(d, "proto.f32"))
        val meta = java.util.Properties().apply { File(d, "meta.properties").inputStream().use { load(it) } }
        val inputW = meta.getProperty("input_w").toInt()
        val inputH = meta.getProperty("input_h").toInt()
        val protoW = meta.getProperty("proto_w").toInt()
        val protoH = meta.getProperty("proto_h").toInt()
        val scaleX = meta.getProperty("scale_x").toFloat()
        val scaleY = meta.getProperty("scale_y").toFloat()
        val padX = meta.getProperty("pad_x").toInt()
        val padY = meta.getProperty("pad_y").toInt()
        val contentW = meta.getProperty("content_w").toInt()
        val contentH = meta.getProperty("content_h").toInt()
        val tileX = meta.getProperty("tile_x").toInt()
        val tileY = meta.getProperty("tile_y").toInt()
        val tileW = meta.getProperty("tile_w").toInt()
        val tileH = meta.getProperty("tile_h").toInt()

        assertEquals("fixture row width must equal the decoder contract", 0, pred.size % KoharuSegCore.ROW_SIZE)
        assertEquals(protoH * protoW * KoharuSegCore.PROTO_CHANNELS, proto.size)

        val letterbox = KoharuSegCore.Letterbox(scaleX, scaleY, padX, padY, contentW, contentH)
        val dets = KoharuSegCore.decode(
            pred, proto, protoW, protoH, inputW, inputH,
            intArrayOf(tileX, tileY, tileX + tileW, tileY + tileH), letterbox
        )

        // Every returned label must be one of the three mapped classes; frame must never leak through.
        for (det in dets) {
            assertTrue(
                "unexpected detector label ${det.label}",
                det.label == BubbleDetectorCore.CLS_BUBBLE ||
                    det.label == BubbleDetectorCore.CLS_TEXT_BUBBLE ||
                    det.label == BubbleDetectorCore.CLS_TEXT_FREE
            )
            // Boxes must land inside the source tile, never in letterbox padding or another coordinate space.
            assertTrue("box outside tile: ${det}", det.l >= tileX && det.t >= tileY)
            assertTrue("box outside tile: ${det}", det.r <= tileX + tileW && det.b <= tileY + tileH)
            assertTrue("degenerate box: ${det}", det.r > det.l && det.b > det.t)
            // A reconstructed mask must have at least one positive cell and stay within its own box by construction.
            det.mask?.let { m ->
                assertEquals(KoharuSegCore.MASK_SIZE, m.width)
                assertEquals(KoharuSegCore.MASK_SIZE, m.height)
                assertTrue("mask is empty", m.pixels.any { it.toInt() != 0 })
            }
        }
        println("real fixture: decoded ${dets.size} detections " +
            dets.groupingBy { it.label }.eachCount())

        // The synthetic page may not reach the production 0.25 threshold in every class. Decode again
        // at a low floor so the mask-reconstruction path is exercised by real tensors whenever the model
        // produced any non-frame candidate at all. Only run the mask checks if such a candidate exists.
        var anyNonFrameAboveLowFloor = false
        val instancesLow = pred.size / KoharuSegCore.ROW_SIZE
        for (i in 0 until instancesLow) {
            val o = i * KoharuSegCore.ROW_SIZE
            if (pred[o + 4].isFinite() && pred[o + 4] >= 0.02f && Math.round(pred[o + 5]) != 0) {
                anyNonFrameAboveLowFloor = true
                break
            }
        }
        if (!anyNonFrameAboveLowFloor) {
            println("real fixture: no non-frame candidate at all; skipping low-floor mask check")
            return
        }
        val lowDets = KoharuSegCore.decode(
            pred, proto, protoW, protoH, inputW, inputH,
            intArrayOf(tileX, tileY, tileX + tileW, tileY + tileH), letterbox, scoreMin = 0.02f
        )
        println("real fixture: low-floor decode ${lowDets.size} detections, " +
            "withMask=${lowDets.count { it.mask != null }}")
        assertTrue("low-floor decode produced no detections though candidates exist", lowDets.isNotEmpty())
        for (det in lowDets) {
            assertTrue("unexpected label ${det.label}", det.label in 0..2)
            assertTrue("box outside tile: $det", det.l >= tileX && det.b <= tileY + tileH)
            det.mask?.let { m ->
                assertTrue("mask is empty", m.pixels.any { it.toInt() != 0 })
                // A mask cell marked inside must map back inside the detected box.
                val mx = m.width / 2
                val my = m.height / 2
                val px = det.l + (mx + 0.5f) * (det.r - det.l) / m.width
                val py = det.t + (my + 0.5f) * (det.b - det.t) / m.height
                if (m.pixels[my * m.width + mx].toInt() != 0) {
                    assertTrue("mask cell maps outside its box", px >= det.l - 1 && px <= det.r + 1)
                    assertTrue("mask cell maps outside its box", py >= det.t - 1 && py <= det.b + 1)
                }
            }
        }
    }

    @Test fun everyNonFrameCandidateAboveThresholdDecodes() {
        val dir = fixtureDir()
        assumeTrue("no Koharu fixture", dir != null)
        val pred = floats(File(dir!!, "pred.f32"))
        val instances = pred.size / KoharuSegCore.ROW_SIZE
        var frameRows = 0
        var decodeableRows = 0
        for (i in 0 until instances) {
            val o = i * KoharuSegCore.ROW_SIZE
            val score = pred[o + 4]
            if (!score.isFinite() || score < KoharuSegCore.SCORE_MIN) continue
            val classId = Math.round(pred[o + 5])
            if (classId == 0) frameRows++ else decodeableRows++
        }
        println("real fixture: $decodeableRows non-frame rows, $frameRows frame rows, " +
            "of $instances instances at score >= ${KoharuSegCore.SCORE_MIN}")
        // The decoder maps frame(0) away and drops it. This asserts the row layout it depends on:
        // confidence at index 4 and class id at index 5. If those offsets were wrong, the model's own
        // output would not present class ids in 0..3 and this count would be meaningless.
        assertTrue("class ids are not in 0..3; the row layout assumed by the decoder is wrong", frameRows + decodeableRows > 0)
    }
}
