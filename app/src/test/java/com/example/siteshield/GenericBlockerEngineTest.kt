package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericBlockerEngineTest {
    private val engine = GenericBlockerEngine()
    private val profile = MangakakalotProfile.profile

    @Test
    fun `allowed host classification accepts configured domain suffix`() {
        assertTrue(engine.isAllowedHost(profile, "www.mangakakalot.gg"))
        assertTrue(engine.isAllowedHost(profile, "cdn.mangakakalot.gg"))
    }

    @Test
    fun `allowed host classification rejects unrelated host`() {
        assertFalse(engine.isAllowedHost(profile, "example.com"))
    }

    @Test
    fun `blocked host classification catches exact blocked suffix`() {
        assertTrue(engine.isBlockedHost(profile, "track.doubleclick.net"))
    }

    @Test
    fun `blocked host classification catches suspicious host token`() {
        assertTrue(engine.isBlockedHost(profile, "cdn-popup-network.test"))
    }

    @Test
    fun `suspicious url detection catches redirect path`() {
        assertTrue(engine.isSuspiciousNavigation(profile, "https://www.mangakakalot.gg/redirect/out"))
    }

    @Test
    fun `suspicious url detection catches non web scheme`() {
        assertTrue(engine.isSuspiciousNavigation(profile, "intent://malicious"))
    }

    @Test
    fun `resource blocking catches ad resource token`() {
        assertTrue(engine.isBlockedResource(profile, "https://cdn.mangakakalot.gg/ads/banner.js"))
    }

    @Test
    fun `normal same site chapter resource is not blocked`() {
        assertFalse(engine.isBlockedResource(profile, "https://www.mangakakalot.gg/chapter/example/chapter-1"))
    }

    @Test
    fun `rule selection keeps preferred profile for unknown resource urls`() {
        val selected = engine.profileForUrl("https://unknown-cdn.example/assets/app.js", profile)

        assertEquals("mangakakalot", selected.id)
    }

    @Test
    fun `suspicious cookie and storage keys use profile patterns`() {
        assertTrue(engine.isSuspiciousCookieKey(profile, "popup_seen"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "redirect_campaign"))
        assertFalse(engine.isSuspiciousCookieKey(profile, "session_id"))
    }
}
