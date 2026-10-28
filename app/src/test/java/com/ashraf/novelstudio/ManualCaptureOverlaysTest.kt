package com.ashraf.novelstudio

import com.ashraf.novelstudio.manualextract.ManualCaptureOverlays
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manual capture must read the ORIGINAL pixels, never our own translation.
 *
 * These pin the hide/restore contract: every app-owned visual layer is addressed, hiding uses `display:none`
 * (a detached node is re-appended by the writer's layout loop), the previous inline value is remembered, and
 * the restore can always run — including after a failed capture — without needing the hide to have succeeded.
 */
class ManualCaptureOverlaysTest {

    @Test fun `hides the translation container and every child layer`() {
        val js = ManualCaptureOverlays.hideScript
        for (id in listOf("__ns_wm_overlay", "__ns_wm_clean_layer", "__ns_wm_cover_layer", "__ns_wm_text_layer")) {
            assertTrue("hide must address $id", js.contains(id))
        }
        // The container alone would be enough to hide painting, but the child layers are addressed too so a page
        // that styles the container itself cannot leak a cover box through.
        assertTrue(ManualCaptureOverlays.layerIds.containsAll(
            listOf("__ns_wm_overlay", "__ns_wm_clean_layer", "__ns_wm_cover_layer", "__ns_wm_text_layer")
        ))
    }

    @Test fun `hiding uses display none instead of removing the node`() {
        val js = ManualCaptureOverlays.hideScript
        assertTrue("must set display:none so the page's own pixels show", js.contains("display='none'"))
        assertFalse("must not remove the node: the layout loop re-appends it", js.contains("removeChild"))
        assertFalse("must not stop the writer", js.contains("__nsWmStop"))
    }

    @Test fun `restore puts back the remembered inline display`() {
        val js = ManualCaptureOverlays.restoreScript
        assertTrue("restore must use the remembered value", js.contains("__nsManualHidden"))
        assertTrue("restore must assign it back", js.contains("display=el.__nsManualHidden"))
        assertFalse("restore must not force a value of its own", js.contains("display='block'"))
    }

    @Test fun `restore is safe even when nothing was hidden`() {
        val js = ManualCaptureOverlays.restoreScript
        // Idempotent: only elements that actually carry the marker are touched.
        assertTrue(js.contains("__nsManualHidden===undefined"))
        assertTrue("wrapped in try/catch so a failed restore cannot throw", js.contains("catch(e)"))
    }

    @Test fun `both scripts report a result the caller can log`() {
        assertTrue(ManualCaptureOverlays.hideScript.contains("'hid:'"))
        assertTrue(ManualCaptureOverlays.restoreScript.contains("'restored:'"))
    }
}
