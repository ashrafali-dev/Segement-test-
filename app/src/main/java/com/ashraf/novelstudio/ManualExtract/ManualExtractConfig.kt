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
     * Longest side sent to the LOCAL recogniser (ML Kit). Never upscaled.
     *
     * The user marked this box, so the crop keeps far more detail than realtime scanning. 1600 px was the
     * remote path's limit and was applied to both engines, which discarded small Korean/Japanese glyphs
     * before ML Kit ever saw them. Local inference has no upload limit; the cap only bounds memory.
     */
    const val MAX_OCR_DIM = 2600
    /**
     * Longest side of a crop that can be sent to the REMOTE recogniser. Lens refuses/degrades larger images,
     * and since [MAX_OCR_DIM] is now higher than this, a mark this large is read locally instead of being
     * shrunk for the network.
     */
    const val MAX_REMOTE_DIM = 1600
    /**
     * Padding around the user's mark, as a fraction of the selection on each side, for the FIRST read.
     *
     * A tighter selection than this clipped the anti-aliased edge of the glyphs it enclosed: the user drew the
     * box on the text, so the text touches the border and half of every edge character is missing. This padding
     * is added around the mark while keeping the crop's own coordinate origin, so results still map back.
     */
    const val OCR_PAD_FIRST = 0.06f
    /** Larger padding for the retry read, which exists precisely because the first read was incomplete. */
    const val OCR_PAD_RETRY = 0.18f
    /** One expanded retry when the crop reads empty OR suspiciously little (never a full-panel retry). */
    const val EXPAND_RETRY = 0.15f
    /**
     * A read is "suspiciously incomplete" when the crop holds clearly more glyph ink than was recognised.
     * Fraction of the crop's ink pixels left unexplained by the recognised boxes.
     */
    const val INCOMPLETE_INK_FRACTION = 0.45f

    // ---- timing caps (ms) ----------------------------------------------------------------------
    /**
     * Whole-run budget. A user-marked region is finished work, not a frame: it gets the time to actually read
     * the text, including the retry. These used to be 4000/1500, which aborted the retry read on any phone that
     * took a moment and silently reported "no text".
     */
    const val TOTAL_CAP_MS = 12000L
    /** Per-read budget passed to the OCR façade (guard against a hung engine, not a target). */
    const val OCR_CAP_MS = 5000L
    const val TRANSLATE_CAP_MS = 2200L

    // ---- caches --------------------------------------------------------------------------------
    /** Bounded LRU of translations keyed by (crop hash + script). */
    const val TRANSLATION_CACHE_ENTRIES = 64

    /** Log tag for every per-step timing line. */
    const val TAG = "MANUAL_EXTRACT"
}
