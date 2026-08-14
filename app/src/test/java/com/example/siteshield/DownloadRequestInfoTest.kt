package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRequestInfoTest {
    @Test
    fun `normalizes optional callback metadata`() {
        assertEquals(
            DownloadRequestInfo(
                url = "https://example.com/report.pdf",
                userAgent = "SiteShield UA",
                contentDisposition = "attachment; filename=report.pdf",
                mimeType = "application/pdf",
                contentLength = 42L,
            ),
            DownloadRequestInfo.fromWebViewCallback(
                " https://example.com/report.pdf ",
                " SiteShield UA ",
                " attachment; filename=report.pdf ",
                "Application/PDF; charset=binary",
                42L,
            ),
        )
    }

    @Test
    fun `missing url is rejected and unknown size is null`() {
        assertNull(DownloadRequestInfo.fromWebViewCallback(null, null, null, null, -1L))
        assertNull(DownloadRequestInfo.fromWebViewCallback(" ", null, null, null, 0L))
        assertNull(
            DownloadRequestInfo.fromWebViewCallback("https://example.com/a", null, null, null, -1L)?.contentLength,
        )
    }
}
