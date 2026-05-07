package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteProfileRegistryTest {
    @Test
    fun `matches mangakakalot profile from start url`() {
        val profile = SiteProfileRegistry.match("https://www.mangakakalot.gg/")

        assertEquals("mangakakalot", profile.id)
    }

    @Test
    fun `matches mangakakalot profile from subdomain host`() {
        val profile = SiteProfileRegistry.match("static.mangakakalot.gg")

        assertEquals("mangakakalot", profile.id)
    }

    @Test
    fun `unknown host uses default profile`() {
        val profile = SiteProfileRegistry.match("https://example.org/")

        assertEquals("default", profile.id)
    }

    @Test
    fun `selectable profiles includes mangakakalot first target`() {
        assertTrue(SiteProfileRegistry.selectableProfiles().any { it.id == "mangakakalot" })
    }
}
