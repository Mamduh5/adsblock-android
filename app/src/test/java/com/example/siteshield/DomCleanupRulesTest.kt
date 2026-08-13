package com.example.siteshield

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DomCleanupRulesTest {
    @Test
    fun `generic overlay heuristics default to enabled`() {
        assertTrue(DomCleanupRules().enableGenericOverlayHeuristics)
    }

    @Test
    fun `disabled baseline cannot be reenabled by default page override`() {
        val merged = DomCleanupRules(enableGenericOverlayHeuristics = false)
            .mergedWith(DomCleanupRules())

        assertFalse(merged.enableGenericOverlayHeuristics)
    }

    @Test
    fun `javascript config serializes overlay heuristic boolean explicitly`() {
        val enabled = DomCleanupRules().toJavascriptObject()
        val disabled = DomCleanupRules(enableGenericOverlayHeuristics = false).toJavascriptObject()

        assertTrue(enabled.contains("\"enableGenericOverlayHeuristics\":true"))
        assertTrue(disabled.contains("\"enableGenericOverlayHeuristics\":false"))
    }

    @Test
    fun `top safe inset uses larger status bar or cutout source`() {
        assertEquals(80, topSafeInsetPx(statusBarTop = 80, displayCutoutTop = 0))
        assertEquals(90, topSafeInsetPx(statusBarTop = 60, displayCutoutTop = 90))
    }
}
