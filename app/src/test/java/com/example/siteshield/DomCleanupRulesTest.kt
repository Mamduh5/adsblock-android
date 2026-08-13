package com.example.siteshield

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DomCleanupRulesTest {
    @Test
    fun `generic overlay heuristics default to enabled`() {
        assertTrue(DomCleanupRules().enableGenericOverlayHeuristics)
        assertTrue(DomCleanupRules().ancestorCleanupRules.isEmpty())
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
    fun `javascript config serializes opt in bounded ancestor cleanup`() {
        val config = DomCleanupRules(
            ancestorCleanupRules = listOf(
                AncestorDomCleanupRule(
                    markerSelector = ".marker",
                    markerTextPrefixes = listOf("Sponsored fixture"),
                    ancestorSelector = ".feed-item",
                    ancestorParentSelector = ".feed",
                    maxAncestorDepth = 4,
                    removalReason = "sponsored-fixture",
                ),
            ),
        ).toJavascriptObject()

        assertTrue(config.contains("\"markerSelector\":\".marker\""))
        assertTrue(config.contains("\"markerTextPrefixes\":[\"Sponsored fixture\"]"))
        assertTrue(config.contains("\"ancestorSelector\":\".feed-item\""))
        assertTrue(config.contains("\"ancestorParentSelector\":\".feed\""))
        assertTrue(config.contains("\"maxAncestorDepth\":4"))
        assertTrue(config.contains("\"removalReason\":\"sponsored-fixture\""))
        assertTrue(config.contains("\"neutralizationStrategy\":\"remove-ancestor\""))
    }

    @Test
    fun `top safe inset uses larger status bar or cutout source`() {
        assertEquals(80, topSafeInsetPx(statusBarTop = 80, displayCutoutTop = 0))
        assertEquals(90, topSafeInsetPx(statusBarTop = 60, displayCutoutTop = 90))
    }

    @Test
    fun `content safe margins preserve independent top and bottom system insets`() {
        val topMargin = topSafeInsetPx(statusBarTop = 60, displayCutoutTop = 90)
        val bottomMargin = bottomSafeInsetPx(navigationBarBottom = 120)

        assertEquals(90, topMargin)
        assertEquals(120, bottomMargin)
    }

    @Test
    fun `bottom safe inset cannot create a negative content margin`() {
        assertEquals(0, bottomSafeInsetPx(navigationBarBottom = -1))
    }
}
