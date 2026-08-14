package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    @Test
    fun `allows legitimate https`() {
        assertEquals(
            DownloadPolicyDecision.Allow("example.com"),
            DownloadPolicy.decide("https://example.com/files/report.pdf"),
        )
    }

    @Test
    fun `rejects cleartext unsafe custom and inline schemes`() {
        assertEquals(
            DownloadBlockReason.CLEAR_TEXT_NOT_ALLOWED,
            (DownloadPolicy.decide("http://example.com/file.pdf") as DownloadPolicyDecision.Block).reason,
        )
        listOf("file:///tmp/a", "content://provider/a", "javascript:alert(1)", "intent://open", "market://details", "fb://post")
            .forEach { url ->
                assertEquals(
                    url,
                    DownloadBlockReason.UNSUPPORTED_SCHEME,
                    (DownloadPolicy.decide(url) as DownloadPolicyDecision.Block).reason,
                )
            }
        listOf("blob:https://example.com/id", "data:text/plain,hello", "filesystem:https://example.com/a")
            .forEach { url ->
                assertEquals(
                    url,
                    DownloadBlockReason.UNSUPPORTED_INLINE_DATA,
                    (DownloadPolicy.decide(url) as DownloadPolicyDecision.Block).reason,
                )
            }
    }

    @Test
    fun `blocks known hostile hosts and malformed urls`() {
        assertEquals(
            DownloadBlockReason.HOSTILE_HOST,
            (DownloadPolicy.decide("https://cdn.popads.net/file.zip") as DownloadPolicyDecision.Block).reason,
        )
        assertTrue(DownloadPolicy.decide("not a url") is DownloadPolicyDecision.Block)
    }
}
