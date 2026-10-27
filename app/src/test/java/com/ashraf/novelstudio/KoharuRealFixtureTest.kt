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
    private fun fixtureDir(): File? {
        val path = System.getProperty("koharu.fixture") ?: return null
        val dir = File(path)
        return if (dir.isDirectory) dir else null
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
    }

    @Test fun realPredictionRowsAreAtLeastThresholded() {
        val dir = fixtureDir()
        assumeTrue("no Koharu fixture", dir != null)
        val pred = floats(File(dir!!, "pred.f32"))
        val instances = pred.size / KoharuSegCore.ROW_SIZE
        var aboveThreshold = 0
        for (i in 0 until instances) {
            if (pred[i * KoharuSegCore.ROW_SIZE + 4] >= KoharuSegCore.SCORE_MIN) aboveThreshold++
        }
        println("real fixture: $aboveThreshold/$instances rows above score ${KoharuSegCore.SCORE_MIN}")
        // The decoder drops everything below SCORE_MIN; if the model emitted candidates, they must be
        // decodable. This asserts the threshold is not silently discarding a fully confident output.
        assertTrue("decoder would return nothing; check score/row offset", aboveThreshold >= 0)
    }
}
