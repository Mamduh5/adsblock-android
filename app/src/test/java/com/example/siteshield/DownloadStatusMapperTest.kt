package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStatusMapperTest {
    @Test
    fun `maps all platform states and unknown values`() {
        assertEquals(DownloadState.QUEUED, DownloadStatusMapper.map(1))
        assertEquals(DownloadState.DOWNLOADING, DownloadStatusMapper.map(2))
        assertEquals(DownloadState.PAUSED, DownloadStatusMapper.map(4))
        assertEquals(DownloadState.COMPLETED, DownloadStatusMapper.map(8))
        assertEquals(DownloadState.FAILED, DownloadStatusMapper.map(16))
        assertEquals(DownloadState.UNKNOWN, DownloadStatusMapper.map(999))
    }

    @Test
    fun `progress is bounded and unknown for invalid totals`() {
        assertEquals(50, DownloadStatusMapper.progressPercent(50, 100))
        assertEquals(100, DownloadStatusMapper.progressPercent(120, 100))
        assertEquals(100, DownloadStatusMapper.progressPercent(Long.MAX_VALUE, Long.MAX_VALUE))
        assertNull(DownloadStatusMapper.progressPercent(10, -1))
        assertNull(DownloadStatusMapper.progressPercent(-1, 100))
    }
}
