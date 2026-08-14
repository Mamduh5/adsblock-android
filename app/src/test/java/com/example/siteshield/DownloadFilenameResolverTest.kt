package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilenameResolverTest {
    @Test
    fun `content disposition wins and supports common forms`() {
        mapOf(
            "attachment; filename=report.pdf" to "report.pdf",
            "attachment; filename=\"report final.pdf\"" to "report final.pdf",
            "inline; filename=\"image.png\"" to "image.png",
            "attachment; filename*=UTF-8''%E0%B8%A3%E0%B8%B2%E0%B8%A2%E0%B8%87%E0%B8%B2%E0%B8%99.pdf" to "รายงาน.pdf",
        ).forEach { (header, expected) ->
            assertEquals(expected, DownloadFilenameResolver.resolve(header, "https://example.com/fallback.bin", null))
        }
    }

    @Test
    fun `falls back through url mime and generic name`() {
        assertEquals("photo.jpg", DownloadFilenameResolver.resolve(null, "https://example.com/files/photo.jpg?x=1", null))
        assertEquals("download.pdf", DownloadFilenameResolver.resolve(null, "https://example.com/?token=private", "application/pdf"))
        assertEquals("download.bin", DownloadFilenameResolver.resolve(null, "https://example.com/", null))
    }

    @Test
    fun `sanitization keeps only a safe leaf and removes controls`() {
        assertEquals("evil.pdf", DownloadFilenameResolver.sanitize("../../evil.pdf"))
        assertEquals("evil.pdf", DownloadFilenameResolver.sanitize("C:\\temp\\evil.pdf"))
        assertEquals("linebreak.pdf", DownloadFilenameResolver.sanitize("line\nbreak.pdf"))
        assertEquals("download.bin", DownloadFilenameResolver.sanitize(".."))
        assertEquals("รายงาน.pdf", DownloadFilenameResolver.sanitize("รายงาน.pdf"))
    }

    @Test
    fun `dangerous extensions and mime types warn without blocking`() {
        assertTrue(DownloadFilenameResolver.isPotentiallyExecutable("app.apk", "application/octet-stream"))
        assertTrue(DownloadFilenameResolver.isPotentiallyExecutable("payload.bin", "application/x-msdownload"))
        assertFalse(DownloadFilenameResolver.isPotentiallyExecutable("report.pdf", "application/pdf"))
    }
}
