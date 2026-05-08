package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugEventTest {
    @Test
    fun `debug event formatting includes category message and detail`() {
        val event = DebugEvent(
            category = DebugEventCategory.NAV_BLOCK,
            message = "Blocked navigation",
            detail = "matchedRule=test-rule",
            timestampMs = 0L,
        )

        val formatted = event.formatted()

        assertTrue(formatted.contains("NAV_BLOCK"))
        assertTrue(formatted.contains("Blocked navigation"))
        assertTrue(formatted.contains("matchedRule=test-rule"))
    }

    @Test
    fun `debug event formatting truncates long messages`() {
        val event = DebugEvent(
            category = DebugEventCategory.RESOURCE_BLOCK,
            message = "x".repeat(500),
            timestampMs = 0L,
        )

        val formatted = event.formatted(maxMessageLength = 40)

        assertTrue(formatted.endsWith("..."))
        assertTrue(formatted.length < 80)
    }

    @Test
    fun `debug log keeps newest events and supports category filtering`() {
        val log = DebugEventLog(maxEvents = 2)

        log.add(DebugEvent(DebugEventCategory.PROFILE, "profile"))
        log.add(DebugEvent(DebugEventCategory.PAGE_TYPE, "page type"))
        log.add(DebugEvent(DebugEventCategory.NAV_BLOCK, "nav"))

        assertEquals(2, log.size())
        assertFalse(log.format().contains("profile"))
        assertTrue(log.format().contains("nav"))
        assertTrue(log.format(DebugEventCategory.NAV_BLOCK).contains("nav"))
        assertFalse(log.format(DebugEventCategory.PAGE_TYPE).contains("nav"))
    }
}
