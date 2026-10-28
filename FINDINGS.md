# FINDINGS — markers, masking, positioning, scan speed

Phase 0 (read-only) verification of the root causes supplied with the task.
Everything below was read in the source branch `fix/rt-ocr-latency-20261026`,
commit `62f8fff`. Each claim cites file:line.

Status legend: **CONFIRMED** = reproduced by reading the code. **REFUTED** =
the code does not behave as described. **NOT FOUND** = searched, does not exist.

---

## A. Marker numbers are not tied to bubbles — CONFIRMED

- `MainActivity.kt:1768-1770` (`buildRealtimeAheadPrompt`) joins every bubble with
  `"\n\n"`, then immediately calls `addInternalMarkers(source)`.
- `MainActivity.kt:8225-8258` (`addInternalMarkers`) re-splits that string:
  - `:8226` `text.split(Regex("\\n\\s*\\n")).map{trim}.filter{isNotEmpty}` — a blank
    line *inside* one bubble's text creates an extra block, and empty text disappears.
  - `:8235-8237` `if (splitSingleLines && blocks.size <= 2)` then `lines.size >= 3`
    replaces blocks with individual lines.
  - `:8244-8252` `chunkBySentences(...)` splits a >500-char block into several.
  - `:8254-8257` `else if (blocks.size <= 2)` … `lines.size >= 6` also replaces blocks
    with lines.

  Therefore "number of markers" ≠ "number of bubbles", so marker `[NNN]` no longer
  identifies bubble `NNN`.
- Other callers: `MainActivity.kt:8453` (`sendJob`) and `:9371`.

**Effect:** any bubble whose text contains a blank line, any bubble with empty text,
any long bubble, or a 1–2 bubble batch with ≥6 lines shifts every following marker.

---

## B. Alignment is all-or-nothing — CONFIRMED

- `WebModeLayout.kt:136` — `if (no < 1 || no > expectedLast) return null`: one
  out-of-range marker discards the **entire** reply.
- `WebModeLayout.kt:156-158` — the `titleOffset` rule returns `null` for the whole
  reply when `[001]` is present but the last marker is missing.
- `WebModeLayout.kt:163` — `found * 100 >= n * 70` returns `null` when fewer than
  70 % of bubbles matched.
- `WebModeLayout.kt:167+` — `alignWithFallback` only rescues the **single-bubble** case.

**Effect:** one bad number from the model discards every good translation in the same
reply.

---

## C. The bubble detector does not drive geometry — CONFIRMED

- `WebModeExtractor.kt:2141` — `detectWanted = !fastMode && preRecognized == null && …`.
  Realtime passes `fastMode = true`, so the ONNX detector **never runs** in realtime.
  The comment at `:2138-2140` explains the original reason (it took ~1.9 s and was
  serialised on `OnnxHeavyGate`), i.e. it was removed for latency, not for correctness.
- `WebModeExtractor.kt:2168-2169` — even the fallback `BubbleDetector.detect(...)`
  requires `!fastMode`.
- `BubbleDetector.kt:43` — `AUTO_MIN_RAM_MB = 3500L`; `BubbleDetector.kt:80` —
  `mode == "auto"` enables only when `DeviceTier.totalRamMb(c) >= 3500`. A phone sold
  as "3.5 GB" reports **less** than 3500 MB via `ActivityManager.totalMem` (the value
  excludes reserved memory), so the detector is silently OFF in the default mode.
- `BubbleDetector.kt:57` — `broken` is sticky until download/delete/mode change.

**Effect:** boxes come from the pixel `BubbleFit` guess, which is why positions and
masks are wrong; and the user has no way to see that detection is off.

---

## D. Old text is not erased properly — CONFIRMED

- `WebModeExtractor.kt:1990` — `if (crop == null && …)` gates cleaning, so a manual
  crop is **never** cleaned.
- `WebModeTextRemover.kt` contains **35** `continue` statements; several silently skip
  a bubble on "ambiguous" grounds, e.g.:
  - `:109` `if (distance(bg, fg1) < 52 && …) continue`
  - `:126` `if (regionSize > 2_000_000L) continue`
  - `:302` `if (selectedCount < 4 || selectedCount > innerArea * (if (soft) 0.82 else 0.6)) continue`
- Erasing is driven by `bubble.cv` bands, which are produced by the pixel `BubbleFit`
  (`WebModeExtractor.kt:2365`), not by the detector.

**Effect:** when the pixel finder does not produce bands (`cv` empty) nothing is
erased and the old lettering stays under the translation.

---

## E. Manual Lite builds one giant, mis-positioned bubble — CONFIRMED

- `ManualExtract/ManualExtractPipeline.kt:146-152` — `l/t/r/btm` is the **union** of
  every OCR block, and `:183-186` builds exactly one `WmBubble` from it. Everything on
  screen therefore becomes one block with one marker.
- `:170` `toParagraph()` joins blocks with `"\n\n"`, and the marker builder (bug A)
  re-splits that string while the layout holds one bubble.
- `:156-157` normalises by `bw = bmp.width`, `bh = bmp.height` — i.e. **the crop** —
  while `src` is the **full image** and the writer treats `cx, cy, w, h, font` as
  full-image-normalised fractions. The crop rect `(x, y, w, h)` is never used to map
  back.
- `ManualExtract/ManualExtractController.kt:67-72` — `startExtractWithBitmap` passes
  `0f, 0f, 1f, 1f`, so a 6-dot frame is treated as the whole image.
- `ManualExtract/ManualExtractOcr.kt` — OCR only: no detector, no grouping, no cleaning.

**Effect:** one huge block, giant font, and positions that belong to the crop rather
than the page.

---

## F. "Double detector" — NOT FOUND

Searched the whole source tree for a second detector:

```
grep -rn "second detector|double detect|Detector2|MultiDetect|dual" \
     app/src/main/java/com/ashraf/novelstudio/
```

→ no match (the only hit, `Prefs.kt:9`, is prompt text).

What actually exists:

| Component | File | What it is |
|---|---|---|
| `BubbleDetector` | `BubbleDetector.kt` | ONE ONNX RT-DETR model, 3 classes: `bubble` / `text_bubble` / `text_free` |
| `BubbleDetectorCore` | `BubbleDetectorCore.kt` | geometry helpers for those detections |
| `BubbleFit` / `BubbleFitCore` | `BubbleFit.kt` | pixel-based bubble-interior finder (no model) |
| `BubbleGrouping` | `BubbleGrouping.kt` | pixel line grouping |
| `OcrRescan`, `OcrLineFusion` | | rescan / line fusion helpers |

**There is no "double detector" in this codebase.** The apparent "two detectors" is
`BubbleDetector` (model) plus `BubbleFit` (pixels), and they are not combined — that
is bug C.

Vertical Japanese columns:

- `BubbleGrouping.kt:15` — `if (a.vertical || b.vertical) return false`: vertical
  blocks are never grouped, so a right-to-left Japanese column block is split apart.

---

## G. What was already verified as NOT a bug (for the record)

- Coordinate scaling in `WebModeExtractor.kt:2372`
  `cx = (x0 + cx * k) / fullW` is already `(sliceOffset + ocrX * sample) / originalWidth`,
  with `fullW` from `BitmapFactory` bounds and `k = sample`. Scaling is correct; the
  wrong placement comes from wrong *boxes* (C) and wrong *marker mapping* (A/B).
- `openCanvas…`/`observeCanvasFrame` lifecycle logic is unrelated to these bugs.
