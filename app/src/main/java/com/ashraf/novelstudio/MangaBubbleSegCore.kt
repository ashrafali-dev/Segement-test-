package com.ashraf.novelstudio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Decoder for mednasserallah/manga109-segmentation-bubble-onnx.
 * Input: [1,3,1024,1024] RGB 0..1, letterboxed.
 * output0: [1,37,21504] = xywh + balloon score + 32 mask coefficients.
 * output1: [1,32,256,256] = 32 mask prototypes. One class: balloon.
 */
internal object MangaBubbleSegCore {
    const val SIZE = 1024
    const val CHANNELS = 37
    const val ANCHORS = 21504
    const val PROTO_CHANNELS = 32
    const val PROTO_SIZE = 256
    const val MASK_SIZE = 64
    const val SCORE_MIN = 0.25f
    const val NMS_IOU = 0.45f
    const val MAX_DETECTIONS = 80
    private const val MAX_CANDIDATES = 600
    private const val PROTO_PIXELS = PROTO_SIZE * PROTO_SIZE

    data class Letterbox(
        val scaleX: Float, val scaleY: Float, val padX: Int, val padY: Int,
        val contentWidth: Int, val contentHeight: Int
    )

    private data class Candidate(
        val anchor: Int, val score: Float, val l: Int, val t: Int, val r: Int, val b: Int,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float
    ) {
        val area: Long get() = (r - l).toLong() * (b - t)
    }

    fun decode(
        predictions: FloatArray, prototypes: FloatArray, tile: IntArray, letterbox: Letterbox,
        scoreMin: Float = SCORE_MIN
    ): List<BubbleDetectorCore.Det> {
        require(predictions.size >= CHANNELS * ANCHORS) { "prediction output too small: ${predictions.size}" }
        require(prototypes.size >= PROTO_CHANNELS * PROTO_PIXELS) { "prototype output too small: ${prototypes.size}" }
        require(tile.size >= 4 && letterbox.scaleX > 0f && letterbox.scaleY > 0f) { "invalid tile/letterbox" }

        val tileW = (tile[2] - tile[0]).coerceAtLeast(1)
        val tileH = (tile[3] - tile[1]).coerceAtLeast(1)
        val minX = letterbox.padX.toFloat()
        val minY = letterbox.padY.toFloat()
        val maxX = (letterbox.padX + letterbox.contentWidth).toFloat()
        val maxY = (letterbox.padY + letterbox.contentHeight).toFloat()
        val candidates = ArrayList<Candidate>()

        // YOLO ONNX output is channel-first: predictions[channel * ANCHORS + anchor].
        for (i in 0 until ANCHORS) {
            val score = predictions[4 * ANCHORS + i]
            if (!score.isFinite() || score < scoreMin) continue
            val cx = predictions[i]
            val cy = predictions[ANCHORS + i]
            val bw = predictions[2 * ANCHORS + i]
            val bh = predictions[3 * ANCHORS + i]
            if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite() || bw <= 1f || bh <= 1f) continue

            val x1 = max(cx - bw * 0.5f, minX).coerceAtMost(maxX)
            val y1 = max(cy - bh * 0.5f, minY).coerceAtMost(maxY)
            val x2 = min(cx + bw * 0.5f, maxX).coerceAtLeast(minX)
            val y2 = min(cy + bh * 0.5f, maxY)
            if (x2 - x1 < 6f || y2 - y1 < 6f) continue

            val l = floor((x1 - letterbox.padX) / letterbox.scaleX).toInt().coerceIn(0, tileW) + tile[0]
            val t = floor((y1 - letterbox.padY) / letterbox.scaleY).toInt().coerceIn(0, tileH) + tile[1]
            val r = ceil((x2 - letterbox.padX) / letterbox.scaleX).toInt().coerceIn(0, tileW) + tile[0]
            val b = ceil((y2 - letterbox.padY) / letterbox.scaleY).toInt().coerceIn(0, tileH) + tile[1]
            if (r - l < 6 || b - t < 6) continue
            candidates.add(Candidate(i, score, l, t, r, b, x1, y1, x2, y2))
        }

        if (candidates.isEmpty()) return emptyList()
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Candidate>(min(MAX_DETECTIONS, candidates.size))
        for (candidate in candidates.take(MAX_CANDIDATES)) {
            if (kept.any { iou(candidate, it) > NMS_IOU }) continue
            kept.add(candidate)
            if (kept.size >= MAX_DETECTIONS) break
        }

        return kept.map { c ->
            val coeff = FloatArray(PROTO_CHANNELS) { ch -> predictions[(5 + ch) * ANCHORS + c.anchor] }
            BubbleDetectorCore.Det(
                BubbleDetectorCore.CLS_BUBBLE, c.score, c.l, c.t, c.r, c.b,
                makeMask(c, coeff, prototypes, tile, letterbox)
            )
        }
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val iw = min(a.r, b.r) - max(a.l, b.l)
        val ih = min(a.b, b.b) - max(a.t, b.t)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw.toLong() * ih
        val union = a.area + b.area - inter
        return if (union <= 0L) 0f else inter.toFloat() / union
    }

    /** Store only a 64x64 instance mask, with each sample cropped to the balloon's own box. */
    private fun makeMask(
        c: Candidate, coefficients: FloatArray, prototypes: FloatArray,
        tile: IntArray, lb: Letterbox
    ): BubbleDetectorCore.Mask? {
        val pixels = ByteArray(MASK_SIZE * MASK_SIZE)
        var positives = 0
        for (my in 0 until MASK_SIZE) {
            val pageY = c.t + (my + 0.5f) * (c.b - c.t) / MASK_SIZE
            val modelY = (pageY - tile[1]) * lb.scaleY + lb.padY
            val py = floor(modelY * PROTO_SIZE / SIZE).toInt().coerceIn(0, PROTO_SIZE - 1)
            for (mx in 0 until MASK_SIZE) {
                val pageX = c.l + (mx + 0.5f) * (c.r - c.l) / MASK_SIZE
                val modelX = (pageX - tile[0]) * lb.scaleX + lb.padX
                val px = floor(modelX * PROTO_SIZE / SIZE).toInt().coerceIn(0, PROTO_SIZE - 1)
                val p = py * PROTO_SIZE + px
                var logit = 0f
                for (ch in 0 until PROTO_CHANNELS) {
                    logit += coefficients[ch] * prototypes[ch * PROTO_PIXELS + p]
                }
                if (logit > 0f) {
                    pixels[my * MASK_SIZE + mx] = 1
                    positives++
                }
            }
        }
        // Keep the valid box if the mask is unusable on this model/runtime.
        return if (positives >= 8) BubbleDetectorCore.Mask(MASK_SIZE, MASK_SIZE, pixels) else null
    }
}
