package com.ashraf.novelstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry gates for the optional second-pass Google Lens audit. */
class OcrRescanTest {
    @Test
    fun skipsSmallRepeatedFalsePositiveRegions() {
        assertFalse(OcrRescan.isUsefulRemoteAuditRegion(OcrRescan.Region(0, 0, 99, 48)))
        assertFalse(OcrRescan.isUsefulRemoteAuditRegion(OcrRescan.Region(0, 0, 96, 48)))
        assertFalse(OcrRescan.isUsefulRemoteAuditRegion(OcrRescan.Region(0, 0, 99, 60)))
        assertFalse(OcrRescan.isUsefulRemoteAuditRegion(OcrRescan.Region(0, 0, 126, 87)))
    }

    @Test
    fun retainsSubstantialPotentialTextRegions() {
        assertTrue(OcrRescan.isUsefulRemoteAuditRegion(OcrRescan.Region(0, 0, 434, 96)))
        assertTrue(OcrRescan.isUsefulRemoteAuditRegion(OcrRescan.Region(0, 0, 160, 100)))
    }
}
