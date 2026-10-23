// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.postprocess.DBPostProcessor
import com.paddle.ocr.preprocess.DetPreprocessResult
import com.paddle.ocr.preprocess.DetPreprocessor
import org.opencv.core.Mat

class DetectionEngine(
    private val ortManager: ORTSessionManager,
    private val config: PaddleOCRConfig,
) {
    data class DetectionResult(
        val boxes: List<OCRBox>,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val timeMs: Long,
        val inputShape: List<Int>,
    )

    // Adaptive caps: start from the config and shrink (stickily, for this engine's lifetime) whenever ONNX Runtime
    // runs out of memory, so one big screenshot does not force every later scan to fail and fall back to ML Kit.
    @Volatile private var sideCap: Int = config.detMaxSideLimit
    @Volatile private var pixelCap: Long = config.detMaxPixels

    private fun isMemoryFailure(t: Throwable): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is OutOfMemoryError) return true
            val m = c.message ?: ""
            if (m.contains("bad_alloc", true) || m.contains("out of memory", true) ||
                m.contains("failed to allocate", true) || m.contains("alloc", true) && m.contains("fail", true)) return true
            c = c.cause
        }
        return false
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        return detect { side, px ->
            DetPreprocessor.preprocess(
                bitmap,
                config.detLimitSideLen,
                config.detLimitType,
                side,
                config.detImgMode,
                px,
            )
        }
    }

    fun detect(src: Mat): DetectionResult {
        return detect { side, px ->
            DetPreprocessor.preprocess(
                src,
                config.detLimitSideLen,
                config.detLimitType,
                side,
                config.detImgMode,
                px,
            )
        }
    }

    private fun detect(preprocessor: (Int, Long) -> DetPreprocessResult): DetectionResult {
        var attempt = 0
        while (true) {
            try {
                return detectOnce { preprocessor(sideCap, pixelCap) }
            } catch (t: Throwable) {
                val retriable = (t is OCRError.InferenceFailed || t is OutOfMemoryError) && isMemoryFailure(t)
                if (!retriable || attempt >= 3 || pixelCap <= 300_000L) throw t
                attempt++
                sideCap = (sideCap * 0.7).toInt().coerceAtLeast(640)
                pixelCap = (pixelCap * 0.6).toLong().coerceAtLeast(300_000L)
                Log.w("NovelStudioPaddle", "detector out of memory; retry #$attempt with maxSide=$sideCap maxPixels=$pixelCap")
            }
        }
    }

    private fun detectOnce(preprocessor: () -> DetPreprocessResult): DetectionResult {
        val preStart = System.currentTimeMillis()
        val preResult = preprocessor()
        val preprocessMs = System.currentTimeMillis() - preStart

        val infStart = System.currentTimeMillis()
        val (outputData, outputShape) = ortManager.runDetection(preResult.tensorData, preResult.shape)
        val inferenceMs = System.currentTimeMillis() - infStart

        val postStart = System.currentTimeMillis()
        val boxes = DBPostProcessor.process(
            pred = outputData,
            predShape = outputShape,
            thresh = config.detThresh,
            boxThresh = config.detBoxThresh,
            unclipRatio = config.detUnclipRatio,
            maxCandidates = config.detMaxCandidates,
            useDilation = config.detUseDilation,
            scoreMode = config.detScoreMode,
            boxType = config.detBoxType,
            originalH = preResult.originalH,
            originalW = preResult.originalW,
        )
        val postprocessMs = System.currentTimeMillis() - postStart

        return DetectionResult(
            boxes = boxes,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            timeMs = preprocessMs + inferenceMs + postprocessMs,
            inputShape = listOf(1, 3, preResult.shape[2].toInt(), preResult.shape[3].toInt()),
        )
    }
}
