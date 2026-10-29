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
assert.ok(!cleaner.includes('disallowed-by-fast-path'),
  'legacy fast-path AI-deny policy must be removed');
assert.ok(extractor.includes('allowAi = req.ahead <= 0') &&
          extractor.includes('residual_max=') &&
          extractor.includes('BUBBLE_MASK_FALLBACK'),
  'realtime cleanup, residual summary and missing-bubble fallback must be wired; read-ahead keeps MI-GAN off');
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
          timing.includes('avgCold=') && timing.includes('avgHot='),
  'timing buckets must measure cold/hot Lens panels and actual Lens call count');
assert.ok(mainActivity.includes('ALIGN_WAIT') && mainActivity.includes('markers_never_arrived') &&
          mainActivity.includes('ALIGN_RETRY') && mainActivity.includes('BATCH_ALIGN_START') &&
          mainActivity.includes('BATCH_ALIGN_DONE') && mainActivity.includes('placedCount * 100 < lay.bubbles.size * 90'),
  'scanning alignment must wait, retry once and enforce 90 percent placement');
assert.ok(cleaner.includes('Semaphore(2, true)') && cleaner.includes('migan_skipped_oom') &&
          cleaner.includes('released panelBitmap freedKB=') && painter.includes('releaseRuntimeForMemoryPressure'),
  'cleaner concurrency and MI-GAN memory guards must be wired');

console.log('PASS: OCR quality wiring (short dialogue, cache telemetry, batch alignment, timing buckets, memory guard, tiered cleaner, bundled MI-GAN)');
