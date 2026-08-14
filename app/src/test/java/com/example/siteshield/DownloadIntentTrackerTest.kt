package com.example.siteshield

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentTrackerTest {
    @Test
    fun `recent gesture permits exactly one download`() {
        var now = 1_000L
        val tracker = DownloadIntentTracker(validityMs = 1_500L) { now }

        tracker.recordGesture()
        now += 500L

        assertTrue(tracker.consumeIfRecent())
        assertFalse(tracker.consumeIfRecent())
    }

    @Test
    fun `missing expired and backward-clock gestures are rejected`() {
        var now = 2_000L
        val tracker = DownloadIntentTracker(validityMs = 1_500L) { now }
        assertFalse(tracker.consumeIfRecent())

        tracker.recordGesture()
        now += 1_501L
        assertFalse(tracker.consumeIfRecent())

        tracker.recordGesture()
        now -= 10L
        assertFalse(tracker.consumeIfRecent())
    }
}
