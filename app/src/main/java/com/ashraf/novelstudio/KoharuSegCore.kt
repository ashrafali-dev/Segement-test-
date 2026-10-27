package com.ashraf.novelstudio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decoder for the dynamic-shape Koharu YOLO26s-seg ONNX export.
 *
 * Contract (ShiniShiho/koharu-yolo26s-onnx, pinned revision
 * e8d0110da6281b93ee6f6509c741931c49ace9fe, 41,604,574 bytes,
 * SHA-256 25a0b06c4ce7ee7c247068a6b3633f98d0f5e913c26ef1cbd06bec3407e914f4):
 * input:  [1,3,H,W], RGB float 0..1, letterboxed with RGB 114; H/W multiples of 32.
 * output0:[1,instances,38], rows = x1,y1,x2,y2,confidence,class_id,32 mask coefficients.
 * output1:[1,32,H/4,W/4], shared mask prototypes (sigmoid applied).
 *
 * Verified against the real graph (scripts/koharu_model_check.py + CI): a 512x384 input
 * yields output0 [1,300,38] (instances-major) and output1 [1,32,128,96]. The rows are
 * xyxy + (top-1 class score) + (that class index) + 32 mask logit coefficients — NOT four
 * per-class probabilities. The head is end-to-end, so no NMS is required, but it re-emits a
 * few same-class duplicates (deduped by IoU in decode()).
 *
 * Koharu classes are mapped into NovelStudio's existing detector contract:
 * 0 frame -> ignored, 1 dialogue_text -> CLS_TEXT_BUBBLE,
 * 2 balloon -> CLS_BUBBLE, 3 onomatopoeia_text -> CLS_TEXT_FREE.
 *
 * Limitation: Koharu has no dedicated "whisper" class. Whisper/narration lettering is
 * classified as dialogue_text or onomatopoeia_text depending on its appearance; the pipeline
 * keeps it separate from a neighbouring balloon using the instance mask (see freeAt/maskCoverage
 * in WebModeExtractor), not a model class.
 */
internal object KoharuSegCore {
    const val DEFAULT_SIZE = 512
    const val STRIDE = 32
    // Dynamic Koharu supports rectangular stride-aligned inputs. After aspect-preserving resize,
    // a 384px-long webtoon tile can be 224x384; rejecting widths below 256 breaks normal inference.
    const val MIN_INPUT_SIZE = 128
    const val MAX_INPUT_SIZE = 1280
    const val ROW_SIZE = 38
    const val PROTO_CHANNELS = 32
    const val MASK_SIZE = 64
    const val SCORE_MIN = 0.25f
    const val DUPLICATE_IOU = 0.70f
    const val MAX_DETECTIONS = 150
    private const val COEFF_OFFSET = 6

    data class Letterbox(
        val scaleX: Float,
        val scaleY: Float,
        val padX: Int,
        val padY: Int,
        val contentWidth: Int,
        val contentHeight: Int
    )

    private data class Candidate(
        val label: Int,
        val score: Float,
        val l: Int, val t: Int, val r: Int, val b: Int,
        val modelL: Float, val modelT: Float, val modelR: Float, val modelB: Float,
        val coefficients: FloatArray
    ) {
        val area: Long get() = (r - l).toLong() * (b - t)
    }

    fun validInputSize(size: Int): Boolean =
        size in MIN_INPUT_SIZE..MAX_INPUT_SIZE && size % STRIDE == 0
    fun validInputShape(width: Int, height: Int): Boolean = validInputSize(width) && validInputSize(height)

    /**
     * Normalize the model's prediction tensor to row-major [instances, 38].
     * Official exports use [1, instances, 38], but some ONNX exporters transpose it to [1, 38, instances].
     * Names such as output0/output1 do not identify the axis order; trust runtime tensor dimensions instead.
     */
    fun normalizePredictionRows(values: FloatArray, shape: LongArray): FloatArray {
        require(shape.size == 3 && (shape[0] == 1L || shape[0] <= 0L)) {
            "Koharu output0 runtime shape is invalid: ${shape.contentToString()}"
        }
        when {
            shape[2] == ROW_SIZE.toLong() -> {
                require(values.isNotEmpty() && values.size % ROW_SIZE == 0) {
                    "Koharu output0 shape ${shape.contentToString()} has ${values.size} values; expected rows of $ROW_SIZE"
                }
                return values
            }
            shape[1] == ROW_SIZE.toLong() && shape[2] > 0L -> {
                val instances = shape[2].toInt()
                require(values.size == ROW_SIZE * instances) {
                    "Koharu output0 shape ${shape.contentToString()} has ${values.size} values"
                }
                val rows = FloatArray(values.size)
                for (instance in 0 until instances) {
                    for (field in 0 until ROW_SIZE) {
                        rows[instance * ROW_SIZE + field] = values[field * instances + instance]
                    }
                }
                return rows
            }
            // Symbolic batch/output dimensions can remain unknown in metadata. The pinned graph documents
            // row-major output0; accept it only when the runtime value count is divisible by the row width.
            shape[1] <= 0L && shape[2] <= 0L && values.isNotEmpty() && values.size % ROW_SIZE == 0 ->
                return values
            else -> throw IllegalArgumentException(
                "Koharu output0 shape ${shape.contentToString()} is incompatible; expected [1,N,38] or [1,38,N]"
            )
        }
    }

    fun decode(
        predictions: FloatArray,
        prototypes: FloatArray,
        protoWidth: Int,
        protoHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
        tile: IntArray,
        letterbox: Letterbox,
        scoreMin: Float = SCORE_MIN
    ): List<BubbleDetectorCore.Det> {
        require(validInputShape(inputWidth, inputHeight)) {
            "invalid Koharu input shape: ${inputWidth}x${inputHeight}"
        }
        require(predictions.size >= ROW_SIZE) { "Koharu prediction output is too small: ${predictions.size}" }
        require(predictions.size % ROW_SIZE == 0) { "Koharu output0 must use [1,instances,38] rows" }
        require(protoWidth > 0 && protoHeight > 0 &&
            prototypes.size >= PROTO_CHANNELS * protoWidth * protoHeight) {
            "Koharu output1 has an invalid prototype shape: ${protoWidth}x${protoHeight}"
        }
        require(tile.size >= 4 && letterbox.scaleX > 0f && letterbox.scaleY > 0f) {
            "invalid tile/letterbox"
        }

        val tileW = (tile[2] - tile[0]).coerceAtLeast(1)
        val tileH = (tile[3] - tile[1]).coerceAtLeast(1)
        val minX = letterbox.padX.toFloat()
        val minY = letterbox.padY.toFloat()
        val maxX = (letterbox.padX + letterbox.contentWidth).toFloat()
        val maxY = (letterbox.padY + letterbox.contentHeight).toFloat()
        val instanceCount = min(predictions.size / ROW_SIZE, 300)
        val candidates = ArrayList<Candidate>()

        for (i in 0 until instanceCount) {
            val o = i * ROW_SIZE
            val score = predictions[o + 4]
            if (!score.isFinite() || score < scoreMin) continue
            val classId = predictions[o + 5].roundToInt()
            val legacyLabel = when (classId) {
                1 -> BubbleDetectorCore.CLS_TEXT_BUBBLE
                2 -> BubbleDetectorCore.CLS_BUBBLE
                3 -> BubbleDetectorCore.CLS_TEXT_FREE
                else -> continue // frame (class 0) is intentionally not a text/bubble detection
            }

            val rawL = predictions[o]
            val rawT = predictions[o + 1]
            val rawR = predictions[o + 2]
            val rawB = predictions[o + 3]
            if (!rawL.isFinite() || !rawT.isFinite() || !rawR.isFinite() || !rawB.isFinite() ||
                rawR <= rawL || rawB <= rawT) continue

            // Koharu outputs xyxy in input-image pixels (not cxcywh and not channel-first anchors).
            val x1 = max(rawL, minX).coerceAtMost(maxX)
            val y1 = max(rawT, minY).coerceAtMost(maxY)
            val x2 = min(rawR, maxX).coerceAtLeast(minX)
            val y2 = min(rawB, maxY).coerceAtLeast(minY)
            if (x2 - x1 < 4f || y2 - y1 < 4f) continue

            val l = floor((x1 - letterbox.padX) / letterbox.scaleX).toInt().coerceIn(0, tileW) + tile[0]
            val t = floor((y1 - letterbox.padY) / letterbox.scaleY).toInt().coerceIn(0, tileH) + tile[1]
            val r = ceil((x2 - letterbox.padX) / letterbox.scaleX).toInt().coerceIn(0, tileW) + tile[0]
            val b = ceil((y2 - letterbox.padY) / letterbox.scaleY).toInt().coerceIn(0, tileH) + tile[1]
            if (r - l < 4 || b - t < 4) continue
            val coeff = FloatArray(PROTO_CHANNELS) { ch -> predictions[o + COEFF_OFFSET + ch] }
            if (coeff.any { !it.isFinite() }) continue
            candidates.add(Candidate(legacyLabel, score, l, t, r, b, x1, y1, x2, y2, coeff))
        }

        // This export is end-to-end and does not need NMS. Its own model card notes that a few
        // same-class duplicate instances remain, so only collapse high-IoU duplicates per class.
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Candidate>(min(MAX_DETECTIONS, candidates.size))
        for (candidate in candidates) {
            if (kept.any { it.label == candidate.label && iou(candidate, it) > DUPLICATE_IOU }) continue
            kept.add(candidate)
            if (kept.size >= MAX_DETECTIONS) break
        }

        return kept.map { c ->
            BubbleDetectorCore.Det(
                c.label, c.score, c.l, c.t, c.r, c.b,
                makeMask(c, prototypes, protoWidth, protoHeight, inputWidth, inputHeight)
            )
        }
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val iw = min(a.r, b.r) - max(a.l, b.l)
        val ih = min(a.b, b.b) - max(a.t, b.t)
        if (iw <= 0 || ih <= 0) return 0f
        val intersection = iw.toLong() * ih
        val union = a.area + b.area - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union
    }

    /** Produce one compact 64x64 mask cropped to this instance's own box. */
    private fun makeMask(
        c: Candidate,
        prototypes: FloatArray,
        protoWidth: Int,
        protoHeight: Int,
        inputWidth: Int,
        inputHeight: Int
    ): BubbleDetectorCore.Mask? {
        val pixels = ByteArray(MASK_SIZE * MASK_SIZE)
        var positive = 0
        val protoPlane = protoWidth * protoHeight
        for (my in 0 until MASK_SIZE) {
            val modelY = c.modelT + (my + 0.5f) * (c.modelB - c.modelT) / MASK_SIZE
            val py = floor(modelY * protoHeight / inputHeight).toInt().coerceIn(0, protoHeight - 1)
            for (mx in 0 until MASK_SIZE) {
                val modelX = c.modelL + (mx + 0.5f) * (c.modelR - c.modelL) / MASK_SIZE
                val px = floor(modelX * protoWidth / inputWidth).toInt().coerceIn(0, protoWidth - 1)
                val p = py * protoWidth + px
                var logit = 0f
                for (ch in 0 until PROTO_CHANNELS) {
                    logit += c.coefficients[ch] * prototypes[ch * protoPlane + p]
                }
                if (logit > 0f) {
                    pixels[my * MASK_SIZE + mx] = 1
                    positive++
                }
            }
        }
        return if (positive >= 8) BubbleDetectorCore.Mask(MASK_SIZE, MASK_SIZE, pixels) else null
    }
}
