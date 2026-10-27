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

        // The synthetic page may not reach the production threshold in every class. Decode again at a
        // low floor so real tensors drive mask reconstruction whenever the model produced any non-frame
        // candidate. If every non-frame row is near-zero noise on this synthetic drawing, promote the
        // single highest-scoring non-frame row's confidence so its REAL coefficients are still pushed
        // through makeMask against the REAL prototype tensor — proving reconstruction with real data.
        var bestNonFrame = -1
        var bestScore = Float.NEGATIVE_INFINITY
        val instancesLow = pred.size / KoharuSegCore.ROW_SIZE
        for (i in 0 until instancesLow) {
            val o = i * KoharuSegCore.ROW_SIZE
            val s = pred[o + 4]
            if (s.isFinite() && Math.round(pred[o + 5]) != 0 && s > bestScore) {
                bestScore = s
                bestNonFrame = i
            }
        }
        assertTrue("model returned only frame rows; the class layout may be wrong", bestNonFrame >= 0)
        val decodeInput = pred.copyOf()
        // Force the chosen real row to clear the floor (only its confidence is touched; coefficients,
        // box and class id are the model's own values).
        decodeInput[bestNonFrame * KoharuSegCore.ROW_SIZE + 4] = Math.max(
            pred[bestNonFrame * KoharuSegCore.ROW_SIZE + 4], 0.5f
        )
        // Box of the promoted row, in model input pixels.
        val ob = bestNonFrame * KoharuSegCore.ROW_SIZE
        val bwRaw = pred[ob + 2] - pred[ob]
        val bhRaw = pred[ob + 3] - pred[ob + 1]
        val boxDecodable = bwRaw.isFinite() && bhRaw.isFinite() && bwRaw > 4f && bhRaw > 4f

        val lowDets = KoharuSegCore.decode(
            decodeInput, proto, protoW, protoH, inputW, inputH,
            intArrayOf(tileX, tileY, tileX + tileW, tileY + tileH), letterbox, scoreMin = 0.5f
        )
        val promotedLabel = Math.round(pred[ob + 5])
        val expectedLabel = when (promotedLabel) {
            1 -> BubbleDetectorCore.CLS_TEXT_BUBBLE
            2 -> BubbleDetectorCore.CLS_BUBBLE
            3 -> BubbleDetectorCore.CLS_TEXT_FREE
            else -> -1
        }
        println("real fixture: promoted row class=$promotedLabel box=${bwRaw}x${bhRaw} " +
            "decodable=$boxDecodable -> ${lowDets.size} detections, " +
            "withMask=${lowDets.count { it.mask != null }}")

        if (boxDecodable) {
            val det = lowDets.firstOrNull { it.label == expectedLabel }
            assertTrue("promoted real row did not decode; class mapping/box reverse is wrong", det != null)
            // The mask must reconstruct from the model's own coefficients and prototypes.
            val m = det!!.mask
            assertTrue("real coefficients produced no usable mask", m != null)
            assertTrue("mask is empty", m!!.pixels.any { it.toInt() != 0 })
            // A mask cell marked inside must map back inside the detected box.
            val mx = m.width / 2
            val my = m.height / 2
            if (m.pixels[my * m.width + mx].toInt() != 0) {
                val px = det.l + (mx + 0.5f) * (det.r - det.l) / m.width
                val py = det.t + (my + 0.5f) * (det.b - det.t) / m.height
                assertTrue("mask cell maps outside its box", px >= det.l - 1 && px <= det.r + 1)
                assertTrue("mask cell maps outside its box", py >= det.t - 1 && py <= det.b + 1)
            }
        } else {
            println("real fixture: promoted row has a degenerate box; decoder correctly dropped it")
        }
    }

    @Test fun everyNonFrameCandidateAboveThresholdDecodes() {
        val dir = fixtureDir()
        assumeTrue("no Koharu fixture", dir != null)
        val pred = floats(File(dir!!, "pred.f32"))
        val instances = pred.size / KoharuSegCore.ROW_SIZE
        var frameRows = 0
        var decodeableRows = 0
        var scoredRows = 0
        val badClassSamples = ArrayList<Float>()
        for (i in 0 until instances) {
            val o = i * KoharuSegCore.ROW_SIZE
            val score = pred[o + 4]
            if (!score.isFinite() || score < KoharuSegCore.SCORE_MIN) continue
            scoredRows++
            val classRaw = pred[o + 5]
            val classId = Math.round(classRaw)
            // The decoder reads class id at index 5. On the real head this is a single integer class index
            // in 0..3. If it were actually the start of four per-class probabilities (the Liiesl card's
            // layout), these values would not be near-integers in 0..3.
            if (Math.abs(classRaw - classId) > 0.01f || classId !in 0..3) {
                if (badClassSamples.size < 5) badClassSamples.add(classRaw)
            }
            if (classId == 0) frameRows++ else decodeableRows++
        }
        println("real fixture: $decodeableRows non-frame rows, $frameRows frame rows, " +
            "$scoredRows scored rows of $instances instances at score >= ${KoharuSegCore.SCORE_MIN}; " +
            "bad class samples=$badClassSamples")
        assertTrue("no scored rows at all; the confidence offset (4) is wrong", scoredRows > 0)
        assertTrue(
            "class ids are not integers in 0..3; the decoder's class-id offset (5) or row layout is wrong: $badClassSamples",
            badClassSamples.isEmpty()
        )
    }
}
