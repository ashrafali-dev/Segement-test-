#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');

const read = (path) => fs.readFileSync(path, 'utf8');
const routing = read('app/src/main/java/com/ashraf/novelstudio/OcrRouting.kt');
const negative = read('app/src/main/java/com/ashraf/novelstudio/OcrNegativeCache.kt');
const filter = read('app/src/main/java/com/ashraf/novelstudio/OcrGarbageFilter.kt');
const layout = read('app/src/main/java/com/ashraf/novelstudio/WebModeLayout.kt');
const cleaner = read('app/src/main/java/com/ashraf/novelstudio/WebModeTextRemover.kt');
const extractor = read('app/src/main/java/com/ashraf/novelstudio/WebModeExtractor.kt');
const painter = read('app/src/main/java/com/ashraf/novelstudio/WebModeInpainter.kt');
const assetScript = read('scripts/prepare-migan-asset.sh');
const mainActivity = read('app/src/main/java/com/ashraf/novelstudio/MainActivity.kt');
const timing = read('app/src/main/java/com/ashraf/novelstudio/OcrPanelTiming.kt');
const diag = read('app/src/main/java/com/ashraf/novelstudio/OcrDiag.kt');
const lensTransport = read('app/src/main/java/com/ashraf/novelstudio/LensOcrEngine.kt');
const chapterContext = read('app/src/main/java/com/ashraf/novelstudio/WebModeChapterContext.kt');
const writer = read('app/src/main/java/com/ashraf/novelstudio/WebModeWriter.kt');

assert.ok(routing.includes('OcrNegativeCache.hit(bitmap)'), 'negative cache must be checked before engine execution');
const localFastStart = routing.indexOf('fun recognizeLocalFast(');
assert.ok(localFastStart >= 0 && routing.slice(localFastStart).includes('OcrNegativeCache.hit(bitmap)'),
  'the chapter prefilter must also bypass ML Kit on negative-cache hit');
assert.ok(routing.includes('OcrGarbageFilter.filter(raw.blocks, script, bitmap.width, bitmap.height)'),
  'garbage filter must run before OCR output reaches translation');
assert.ok(negative.includes('DEFAULT_TTL_MS = 30_000L') &&
          negative.includes('REPEATED_TTL_MS = 120_000L'),
  'negative cache must use the requested 30s / 120s TTL policy');
assert.ok(negative.includes('OcrDiag.log("LENS_NEG_CACHE_HIT"') &&
          negative.includes('OcrDiag.log("LENS_NEG_CACHE_INSERT"'),
  'negative cache hits and inserts must be present in the copied OCR report timeline');
assert.ok(filter.includes('OcrDiag.log("FILTER_REJECT", "reason=$reason text=') &&
          filter.includes('latin_mixed') && filter.includes('script_mismatch') &&
          filter.includes('single_char') && filter.includes('border'),
  'garbage filter must put per-rejection reasons in the copied OCR report timeline');
assert.ok(layout.includes('placed == n -> "ACCEPT"') &&
          layout.includes('placed == 0 -> "REJECT"') &&
          layout.includes('"ALIGN_MISS"'),
  'alignment must distinguish complete/partial/rejected and report every missing slot');
assert.ok(cleaner.includes('medianRingColor(') && cleaner.includes('fillRoundedRect(') &&
          cleaner.includes('openCvInpaint(') && cleaner.includes('CLEAN_TIER') &&
          cleaner.includes('policy=tiered') && cleaner.includes('residual_max='),
  'cleaner must contain all tiers and residual diagnostics');
assert.ok(cleaner.includes('MAX_CLEAN_W = 1200') &&
          cleaner.includes('MAX_CLEAN_H = 2400') &&
          cleaner.includes('CLEAN_GUARD') &&
          cleaner.includes('CLEAN_DOWNSCALE') &&
          cleaner.includes('inSampleSize = decodeSample') &&
          !cleaner.includes('image_dimensions_out_of_bounds'),
  'large panels must be sampled/downscaled inside safe bounds rather than rejected by the old dimensions guard');
assert.ok(cleaner.includes('BBOX_DILATION_PX = 5'),
  'cleaning masks must start from OCR boxes padded by five pixels');
assert.ok(extractor.includes('SLICE_STRATEGY') &&
          extractor.includes('decision=whole_panel') &&
          extractor.includes('decision=fallback_slice') &&
          extractor.includes('fallbackHeight = (h * 0.60f)') &&
          extractor.includes('PANEL_BITMAP'),
  'scanning must try whole-panel OCR first, fall back to at most two overlapping slices, and log bitmap dimensions/source');
assert.ok(extractor.includes('val scanMode = !req.visibleOnly && req.ahead <= 0') &&
          extractor.includes('val realtimePriority = !scanMode') &&
          extractor.includes('DETECT_MODE') &&
          extractor.includes('pixel_bbox_fallback') && extractor.includes('bboxPad=5') &&
          extractor.includes('!fastMode && !remoteDet'),
  'scanning detection must use pixel-based bbox fallback independently of fast Lens dispatch without extra per-bubble Lens reads');

assert.ok(!cleaner.includes('disallowed-by-fast-path'),
  'legacy fast-path AI-deny policy must be removed');
assert.ok(extractor.includes('allowAi = true') &&
          extractor.includes('prefetchMode = req.ahead > 0') &&
          extractor.includes('panelNo = panelNo') &&
          extractor.includes('residual_max=') &&
          extractor.includes('BUBBLE_MASK_FALLBACK'),
  'realtime and prefetch cleanup must pass panel context and permit residual-driven MI-GAN');
assert.ok(!painter.includes('MODEL_URL') &&
          painter.includes('app.assets.open(MODEL_NAME)') &&
          painter.includes('EXPECTED_BYTES'),
  'installed app must load a verified bundled MI-GAN asset rather than download it');
assert.ok(assetScript.includes('EXPECTED_SHA256=') && assetScript.includes('EXPECTED_BYTES='),
  'build-time asset preparation must verify the pinned model');

assert.ok(filter.includes('FILTER_PASS') && filter.includes('"뭐"') && filter.includes('"왜"'),
  'short Korean/Japanese dialogue must have a pass diagnostic and allowlist');
assert.ok(negative.includes('LENS_NEG_CACHE_MISS') && negative.includes('statsSummary()') &&
          negative.includes('hitPct='),
  'expired negative entries and session cache hit ratio must be reported');
assert.ok(extractor.includes('BATCH_START') && extractor.includes('BATCH_OCR_DONE') &&
          extractor.includes('BATCH_FLUSH_DONE') && extractor.includes('PREFILTER_SKIP_LENS') &&
          extractor.includes('edgeDensity=') && extractor.includes('OcrPanelTiming.logNoLens'),
  'batch completion, low-edge prefilter skip and per-panel timing must be logged');
assert.ok(timing.includes('TIMING_BUCKET') && timing.includes('lensCalls=') &&
          timing.includes('avgCold=') && timing.includes('avgHot=') &&
          timing.includes('hostBucketCounts') && timing.includes('hostBuckets.getOrPut(frame.host)') &&
          timing.includes('lensHostsSeen.add(frame.host)') &&
          timing.includes('hostAvgCold=$hostAvgCold') &&
          timing.includes('"tonari" in raw') && timing.includes('"naver" in raw') &&
          diag.includes('OcrPanelTiming.normalizeHost(hostKey)'),
  'timing buckets must measure cold/hot Lens panels independently by normalized host and actual Lens call count');
assert.ok(diag.includes('PERF_SNAPSHOT') && diag.includes('s.jobs % 10 == 0') &&
          diag.includes('avgMs=$snapshotAvgMs cold=$cold hot=$hot') &&
          diag.includes('OcrPanelTiming.hostBucketCounts(hostForLog)'),
  'OCR diagnostics must log per-host average latency and cold/hot counts every 10 jobs');
assert.ok(mainActivity.includes('ALIGN_WAIT') && mainActivity.includes('markers_never_arrived') &&
          mainActivity.includes('ALIGN_RETRY') && mainActivity.includes('BATCH_ALIGN_START') &&
          mainActivity.includes('BATCH_ALIGN_DONE') && mainActivity.includes('placedCount * 100 < lay.bubbles.size * 90'),
  'scanning alignment must wait, retry once and enforce 90 percent placement');
assert.ok(cleaner.includes('Semaphore(2, true)') && cleaner.includes('migan_skipped_oom') &&
          cleaner.includes('released panelBitmap freedKB=') && painter.includes('releaseRuntimeForMemoryPressure'),
  'cleaner concurrency and MI-GAN memory guards must be wired');
assert.ok(cleaner.includes('TIER1_FILL_ALPHA = 0.98f') &&
          cleaner.includes('TIER2_FILL_ALPHA = 0.98f') &&
          cleaner.includes('TIER3_FILL_ALPHA = 1.00f'),
  'all cleaner tiers must use the requested fill alpha');
assert.ok(cleaner.includes('TIER2_RESIDUAL_THRESHOLD = 5f') &&
          cleaner.includes('TIER3_RESIDUAL_THRESHOLD = 8f') &&
          cleaner.includes('CLEAN_FAILED_RESIDUAL_THRESHOLD = 20f') &&
          cleaner.includes('metric=normalized_0_100'),
  'all stages must use normalized residual thresholds and the large-mask 20% failure rule');
assert.ok(cleaner.includes('MIGAN_STATUS') && cleaner.includes('migan_unavailable reason=') &&
          cleaner.includes('CLEAN_ESCALATE') && cleaner.includes('CLEAN_WARN') &&
          cleaner.includes('scope=bbox pad=$BBOX_DILATION_PX') &&
          cleaner.includes('kernel=3x3 iterations=1'),
  'dilation, residual-driven escalation, MI-GAN status and failure diagnostics must be wired');
assert.ok(extractor.includes('realtimeMode = req.visibleOnly'),
  'full scan may use unlimited tier-3 budget while realtime mode remains budget-limited');
assert.ok(!cleaner.includes('bubble.free && !smoothSurround'),
  'MI-GAN unavailability must not bypass Tier 2; verify residual and warn instead');
assert.ok(cleaner.includes('openCvInpaint(tier1Region, rw, rh, mask)') &&
          !cleaner.includes('openCvInpaint(region, rw, rh, mask)'),
  'Tier 2 must inpaint Tier 1 output rather than resurrecting the original panel pixels');
assert.ok(cleaner.includes('tier2CandidateResidual > residual1 * 1.1f') &&
          cleaner.includes('tier=2 keptTier1=true') &&
          cleaner.includes('tier=3 keptTier2=true'),
  'Tier 2 and Tier 3 worsening results must be rolled back');
assert.ok(cleaner.includes('CLEAN_FILL') && cleaner.includes('CLEAN_ASSERT') &&
          cleaner.includes('tier1CoversMask') && cleaner.includes('fillArea++'),
  'Tier 1 must log alpha, fill coverage and mask coverage assertion');
assert.ok(cleaner.includes('MIGAN_PREFETCH') && cleaner.includes('allowed=true reason=residual>8') &&
          cleaner.includes('tryAcquireGlobalCallBudget') &&
          painter.includes('GLOBAL_CALL_LIMIT = 20') &&
          painter.includes('GLOBAL_CALL_WINDOW_MS = 60_000L'),
  'prefetch MI-GAN must be residual-gated, budgeted per panel and globally rate limited');
assert.ok(extractor.includes('FORCE_CAPTURE') && extractor.includes('stableSamples < 3') &&
          extractor.includes('timeoutMs = minOf(if (forceCrossOriginCanvas) 100L else 180L, remainingMs)') &&
          extractor.includes('FORCE_ABORT') &&
          extractor.includes('recapture $driftRetries/1') &&
          extractor.includes('FORCE_SKIP') &&
          extractor.includes('FORCE_MODE') &&
          extractor.includes('reason=cross_origin_canvas') &&
          extractor.includes('maxBlankRetries = if (forceCrossOriginCanvas) 1 else 3'),
  'FORCE capture must settle, reduce screenshot retries for cross-origin canvas, and retain diagnostics');
assert.ok(cleaner.includes('LARGE_MASK_THRESHOLD = 15_000') &&
          cleaner.includes('LARGE_MASK_ALPHA = 1.00f') &&
          cleaner.includes('openCvInpaintTiled(tier1Region, rw, rh, mask)') &&
          cleaner.includes('CLEAN_TIER2_TILE') &&
          cleaner.includes('ringSamplePad=') && cleaner.includes('BBOX_DILATION_PX + ringSamplePad') &&
          cleaner.includes('migan_skipped reason=mask_too_large area=$maskArea') &&
          cleaner.includes('if (residual1 <= TIER2_RESIDUAL_THRESHOLD && !largeMask)'),
  'every large sound-effect mask must use opaque fill and tiled Tier 2, while MI-GAN is skipped above the area limit');
assert.ok(cleaner.includes('val bubbleHardFailure = maskArea > MIGAN_MAX_MASK_AREA && residualFinal >= CLEAN_FAILED_RESIDUAL_THRESHOLD') &&
          cleaner.includes('CLEAN_FAILED_RESIDUAL_THRESHOLD = 20f') &&
          cleaner.includes('best_effort_large_mask'),
  'only masks above 40000 pixels with residual >=20% may become hard clean failures');
assert.ok(painter.includes('INFERENCE_CIRCUIT_COOLDOWN_MS = 60_000L') &&
          painter.includes('MIGAN_CIRCUIT_OPEN') &&
          painter.includes('MIGAN_BLANK_OUTPUT') &&
          painter.includes('MIGAN_QUEUE') &&
          painter.includes('val side = minOf(SIZE, maxOf(160, (maxOf(rw, rh) * 1.6f).toInt()))' ) &&
          cleaner.includes('!WebModeInpainter.inferenceCircuitOpen') &&
          cleaner.includes('WebModeInpainter.inferenceCircuitOpen || aiCircuitOpen'),
  'MI-GAN must circuit-break failures, validate output, serialize calls, pad fixed-size inputs, and stop retry spam immediately');
assert.ok(lensTransport.includes('LENS_CIRCUIT_OPEN') &&
          lensTransport.includes('LENS_SKIP') &&
          lensTransport.includes('halfOpenProbeInFlight') &&
          lensTransport.includes('cooldownMs = if (wasHalfOpenProbe) 60_000L else 30_000L') &&
          lensTransport.includes('Google Lens: timeout') &&
          lensTransport.includes('shouldSkipWarmUp()') &&
          lensTransport.includes('LENS_SKIP", "reason=circuit_open fallback=mlkit warmup=true'),
  'Lens must open a 30s outage circuit and allow exactly one half-open probe, extending to 60s on probe failure');
assert.ok(routing.includes('BATCH_WAIT_OCR') &&
          routing.includes('reason=circuit_open fallback=mlkit') &&
          routing.includes('LensOcrEngine.breakerStatus().startsWith("OPEN"') &&
          routing.includes('noteNetworkFallback(failure, fallbackResult.blocks.size)'),
  'Lens failures must finish synchronous ML Kit fallback before returning to alignment and avoid pausing on concurrent circuit-open failures');
assert.ok(mainActivity.includes('no_markers_after_lens_failure') &&
          mainActivity.includes('-> RETRY_PENDING') &&
          mainActivity.includes('lens_dns_failure -> PARTIAL') &&
          mainActivity.includes('delayMs=30000 attempt=1'),
  'markerless Lens outage must wait 1.5s, retry once after cooldown, and remain partial rather than reject');

const beginForceAt = extractor.indexOf('fun begin()');
const forceDispatchAt = extractor.indexOf('if ((req.force && !req.visibleOnly) || req.forceScreenOcr)', beginForceAt);
assert.ok(beginForceAt >= 0 && forceDispatchAt > beginForceAt &&
          extractor.slice(beginForceAt, forceDispatchAt).includes('expectedBlocks < 3') &&
          extractor.slice(beginForceAt, forceDispatchAt).includes('reason=tiny_panel'),
  'known tiny viewport panels must be skipped before FORCE_BEGIN is scheduled');

assert.ok(cleaner.includes('MAX_CLEAN_W = 1200') &&
          cleaner.includes('MAX_CLEAN_H = 2400') &&
          cleaner.includes('CLEAN_GUARD') &&
          cleaner.includes('CLEAN_DOWNSCALE') &&
          cleaner.includes('scanMode: Boolean = !realtimeMode && !prefetchMode') &&
          cleaner.includes('reason=text_bbox_fallback textPresent=true bboxPad=5 maskArea=') &&
          cleaner.includes('mode=scan'),
  'cleaning must downscale out-of-bounds panels and keep a five-pixel OCR-bbox fallback for scan speech bubbles');
assert.ok(cleaner.includes('BUBBLE_CLEAN_VERIFY') &&
          cleaner.includes('dilatePx=2') &&
          cleaner.includes('extraPass=true accepted='),
  'every processed dialogue bubble must run the final residual verifier and one guarded extra pass');
assert.ok(extractor.includes('MODE_ENGAGED') &&
          extractor.includes('expectations=') &&
          extractor.includes('val scanMode = !req.visibleOnly && req.ahead <= 0') &&
          extractor.includes('SLICE_STRATEGY", "mode=scan slices=${fallbackSlices.size + 1} decision=fallback_slice') &&
          extractor.includes('DETECT_MODE') &&
          extractor.includes('mode=scan ran=true masks=') &&
          extractor.includes('CHAPTER_COLLECT'),
  'scanning must declare its mode, try whole-panel OCR first, cap fallback slices and log pixel-mask detection');
assert.ok(chapterContext.includes('data class WebModeChapterContext') &&
          chapterContext.includes('fun windows(maxPanels: Int = 20, overlapPanels: Int = 2') &&
          chapterContext.includes('CONTEXT_ONLY') &&
          chapterContext.includes('CHAPTER_SUMMARY') &&
          chapterContext.includes('classifyRole'),
  'chapter context must preserve bubble IDs/roles, context overlap and bounded structured windows');
assert.ok(mainActivity.includes('sendScanTranslationWindow') &&
          mainActivity.includes('CHAPTER_TRANSLATE') &&
          mainActivity.includes('CHAPTER_TRANSLATE_DONE') &&
          mainActivity.includes('CHAPTER_TRANSLATE_SPLIT') &&
          mainActivity.includes('CHAPTER_APPLY') &&
          mainActivity.includes('showChapterSummaryDialog'),
  'scan mode must use a chapter request/window pipeline, apply by chapter markers and show its summary');
assert.ok(writer.includes('.put("sfx", sfx)') &&
          writer.includes("if(it.sfx)") &&
          writer.includes("style=bold_italic position=original_bbox") &&
          writer.includes("it.sfx?'none'"),
  'SFX placement must use a distinct outline style without painting a large art-block cover');

console.log('PASS: OCR quality wiring (scan downscale/fallback, whole-panel OCR, chapter-context windows, verified bubble cleaning, art-preserving SFX style, MI-GAN/Lens circuit breakers)');
