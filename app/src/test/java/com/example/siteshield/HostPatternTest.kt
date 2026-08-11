package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPatternTest {
    private val engine = GenericBlockerEngine()
    private val profile = DefaultProfile.profile

    @Test
    fun `ads dns label blocks exact label and nested subdomain`() {
        assertTrue(engine.isBlockedHost(profile, "ads.example.com"))
        assertTrue(engine.isBlockedHost(profile, "cdn.ads.example.com"))
        assertEquals(
            BlockDecision.Block(BlockReason.SUSPICIOUS_HOST),
            engine.resourceDecision(profile, "https://cdn.ads.example.com/banner.js"),
        )
    }

    @Test
    fun `ads characters inside legitimate labels do not block`() {
        assertFalse(engine.isBlockedHost(profile, "downloads.example.com"))
        assertFalse(engine.isBlockedHost(profile, "uploads.example.com"))
    }

    @Test
    fun `label matching normalizes case www and trailing dot`() {
        assertTrue(engine.isBlockedHost(profile, "WWW.ADS.Example.COM."))
        assertTrue(engine.isBlockedHost(profile, "Cdn-Popup-Network.Example"))
        assertFalse(engine.isBlockedHost(profile, "www.downloads.example.com"))
    }

    @Test
    fun `exact host matching does not include subdomains`() {
        val exact = HostPattern.Exact("blocked.example")

        assertTrue(exact.matches("BLOCKED.EXAMPLE."))
        assertTrue(exact.matches("www.blocked.example"))
        assertFalse(exact.matches("cdn.blocked.example"))
        assertFalse(exact.matches("notblocked.example"))
    }

    @Test
    fun `domain suffix matching includes base and subdomains but not lookalikes`() {
        val suffix = HostPattern.DomainSuffix("blocked.example")

        assertTrue(suffix.matches("blocked.example"))
        assertTrue(suffix.matches("cdn.blocked.example"))
        assertTrue(suffix.matches("WWW.CDN.BLOCKED.EXAMPLE."))
        assertFalse(suffix.matches("notblocked.example"))
        assertFalse(suffix.matches("blocked.example.evil"))
    }

    @Test
    fun `explicit blocked domain suffix behavior remains intact`() {
        assertTrue(engine.isBlockedHost(profile, "doubleclick.net"))
        assertTrue(engine.isBlockedHost(profile, "track.doubleclick.net"))
        assertFalse(engine.isBlockedHost(profile, "notdoubleclick.net"))
    }
}
