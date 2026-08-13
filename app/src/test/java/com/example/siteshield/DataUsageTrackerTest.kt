package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataUsageTrackerTest {
    @Test
    fun `session usage is the nonnegative UID counter delta`() {
        val counter = FakeCounter(1_000, 500)
        val tracker = DataUsageTracker(counter, "facebook")
        counter.set(6_000, 1_500)

        val usage = tracker.snapshot()

        assertEquals(DataUsage(rxBytes = 5_000, txBytes = 1_000), usage.session)
        assertEquals(6_000, usage.session.totalBytes)
        assertEquals(usage.session, usage.byProfile["facebook"])
        assertTrue(usage.countersSupported)
    }

    @Test
    fun `profile switches attribute intervals without double counting`() {
        val counter = FakeCounter(100, 50)
        val tracker = DataUsageTracker(counter, "facebook")
        counter.set(1_100, 550)
        tracker.switchProfile("youtube")
        counter.set(1_600, 1_050)
        tracker.switchProfile("aquareader")

        val first = tracker.snapshot()
        val second = tracker.snapshot()

        assertEquals(DataUsage(1_000, 500), first.byProfile["facebook"])
        assertEquals(DataUsage(500, 500), first.byProfile["youtube"])
        assertEquals(DataUsage(1_500, 1_000), first.session)
        assertEquals(first, second)
    }

    @Test
    fun `unsupported counters remain unavailable and never become negative`() {
        val counter = FakeCounter(null, null)
        val tracker = DataUsageTracker(counter, "facebook")

        val usage = tracker.snapshot()

        assertFalse(usage.countersSupported)
        assertEquals(DataUsage(), usage.session)
    }

    @Test
    fun `counter reset establishes a new baseline without negative or duplicate usage`() {
        val counter = FakeCounter(1_000, 1_000)
        val tracker = DataUsageTracker(counter, "facebook")
        counter.set(1_500, 1_400)
        assertEquals(DataUsage(500, 400), tracker.snapshot().session)

        counter.set(100, 50)
        assertEquals(DataUsage(500, 400), tracker.snapshot().session)
        counter.set(300, 150)

        assertEquals(DataUsage(700, 500), tracker.snapshot().session)
    }

    @Test
    fun `usage addition saturates instead of overflowing`() {
        val usage = DataUsage(Long.MAX_VALUE, Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, usage.totalBytes)
        assertEquals(Long.MAX_VALUE, (usage + DataUsage(1, 1)).rxBytes)
    }

    @Test
    fun `human readable formatter uses actual bytes`() {
        assertEquals("0 B", formatDataBytes(-1))
        assertEquals("512 B", formatDataBytes(512))
        assertEquals("1.5 KB", formatDataBytes(1_536))
        assertEquals("2.0 MB", formatDataBytes(2L * 1024 * 1024))
        assertEquals("1.0 GB", formatDataBytes(1024L * 1024 * 1024))
    }
}

private class FakeCounter(
    rxBytes: Long?,
    txBytes: Long?,
) : NetworkCounterProvider {
    private var current = NetworkCounterSnapshot(rxBytes, txBytes)

    fun set(rxBytes: Long?, txBytes: Long?) {
        current = NetworkCounterSnapshot(rxBytes, txBytes)
    }

    override fun read(): NetworkCounterSnapshot = current
}
