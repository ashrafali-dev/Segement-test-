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

assert.ok(routing.includes('OcrNegativeCache.hit(bitmap)'), 'negative cache must be checked before engine execution');
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

console.log('PASS: OCR quality wiring (negative cache, garbage filter, tiered cleaner, alignment, bundled MI-GAN)');
