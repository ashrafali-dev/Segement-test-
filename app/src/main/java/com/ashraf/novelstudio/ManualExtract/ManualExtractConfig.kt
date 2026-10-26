package com.ashraf.novelstudio.manualextract

/**
 * Tunables for the low-RAM manual-extract mode: one tap = one run (capture → crop → OCR → translate → place),
 * then idle. Nothing here starts work in the background.
 *
 * Kept as plain constants so the mode has no dependency on Prefs wiring beyond the master switch.
 */
internal object ManualExtractConfig {

    /** Master switch for this mode. When false the tap hook does nothing. */
    const val ENABLED = true

    // ---- capture -------------------------------------------------------------------------------
    /** Extra area around the tapped point, as a fraction of the tapped bubble size (each side). */
    const val CAPTURE_MARGIN = 0.35f
    /** Hard floor/ceiling for the captured region, in device pixels. */
    const val MIN_CROP_PX = 40
    // copied from WebModeExtractor.kt (MIN_W=120/MIN_H=60 are panel minimums) — keep in sync
    const val ABORT_IF_LARGER_THAN = 1024

    // ---- OCR -----------------------------------------------------------------------------------
    /**
     * Longest side sent to OCR. Never upscaled.
     *
     * The user marked this box, so the crop keeps far more detail than realtime scanning: shrinking it to
     * 720 px threw away small Japanese/Korean glyphs before the engine saw them. Only oversized selections
     * are scaled, and never below this.
     */
    const val MAX_OCR_DIM = 1600
    /** One slightly-expanded retry when the crop reads empty (never a full-panel retry). */
    const val EXPAND_RETRY = 0.15f

    // ---- timing caps (ms) ----------------------------------------------------------------------
    const val TOTAL_CAP_MS = 4000L
    const val OCR_CAP_MS = 1500L
    const val TRANSLATE_CAP_MS = 2200L

    // ---- caches --------------------------------------------------------------------------------
    /** Bounded LRU of translations keyed by (crop hash + script). */
    const val TRANSLATION_CACHE_ENTRIES = 64

    /** Log tag for every per-step timing line. */
    const val TAG = "MANUAL_EXTRACT"
}
