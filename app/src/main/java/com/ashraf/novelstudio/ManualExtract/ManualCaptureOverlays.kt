package com.ashraf.novelstudio.manualextract

/**
 * Scripts that hide and restore every app-owned visual layer for a manual capture.
 *
 * A manual recovery must read the ORIGINAL pixels. Drawing the WebView while `__ns_wm_overlay` is visible
 * captures translated text, opaque cover boxes, the cleaned-image layer and the app's own replay controls
 * instead of the artwork, and OCR then reads the translation back to itself.
 *
 * Hiding is done with `display:none` (not by removing the element): the writer's `layout()` loop re-appends
 * the overlay whenever it is detached, and it would also rebuild a removed cover/text layer on its next pass.
 * A `display:none` node is left in place and simply not painted, so the original page shows through.
 *
 * The previous inline `display` of every touched element is remembered on the node itself, so [restoreScript]
 * puts each one back exactly (including "no inline value at all").
 *
 * Pure string builders, so the contract is unit-tested without a WebView.
 */
internal object ManualCaptureOverlays {

    /** Layers that are app-owned and must never reach OCR. The container covers all three child layers. */
    private val HIDDEN_IDS = listOf(
        "__ns_wm_overlay",          // translation bubbles + stringified text
        "__ns_wm_clean_layer",      // replaced/cleaned images drawn over the original
        "__ns_wm_cover_layer",      // opaque cover rectangles
        "__ns_wm_text_layer"        // translated label text
    )

    /**
     * Hide every overlay layer and return a token describing what changed, so the restore can be exact and
     * idempotent. Runs synchronously in the page; the result must be evaluated before drawing the WebView.
     */
    val hideScript: String = """
        (function(){
          try {
            var ids=${HIDDEN_IDS.joinToString(",") { "'$it'" }};
            var saved=[];
            for(var i=0;i<ids.length;i++){
              var el=document.getElementById(ids[i]);
              if(!el)continue;
              if(el.__nsManualHidden===undefined)el.__nsManualHidden=(el.style.display||'');
              el.style.display='none';
              saved.push(ids[i]);
            }
            return 'hid:'+saved.length;
          } catch(e) { return 'hide-error:'+e; }
        })()
    """.trimIndent()

    /**
     * Restore every layer hidden by [hideScript] to its remembered inline display. Safe to run when nothing
     * was hidden (the "hid:0" case) and safe to run twice.
     */
    val restoreScript: String = """
        (function(){
          try {
            var w=document.getElementById('__ns_wm_overlay');
            var ids=${HIDDEN_IDS.joinToString(",") { "'$it'" }};
            var restored=0;
            for(var i=0;i<ids.length;i++){
              var el=document.getElementById(ids[i]);
              if(!el)continue;
              if(el.__nsManualHidden===undefined)continue;
              el.style.display=el.__nsManualHidden;
              delete el.__nsManualHidden;
              restored++;
            }
            return 'restored:'+restored;
          } catch(e) { return 'restore-error:'+e; }
        })()
    """.trimIndent()

    /** Ids the two scripts act on; exposed so tests can assert the container and its child layers are covered. */
    internal val layerIds: List<String> = HIDDEN_IDS
}
