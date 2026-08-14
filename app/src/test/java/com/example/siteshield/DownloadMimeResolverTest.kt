package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMimeResolverTest {
    @Test
    fun `callback mime wins when specific`() {
        assertEquals("application/custom", DownloadMimeResolver.resolve("application/custom; charset=utf-8", "file.pdf"))
    }

    @Test
    fun `infers known extensions and safely falls back`() {
        assertEquals("application/pdf", DownloadMimeResolver.resolve(null, "report.pdf"))
        assertEquals("image/jpeg", DownloadMimeResolver.resolve("*/*", "photo.JPG"))
        assertEquals("application/octet-stream", DownloadMimeResolver.resolve(null, "archive.unknown"))
        assertEquals("application/octet-stream", DownloadMimeResolver.resolve(null, "download"))
        assertEquals("application/pdf", DownloadMimeResolver.resolve("application/pdf\nInjected", "report.pdf"))
    }
}
