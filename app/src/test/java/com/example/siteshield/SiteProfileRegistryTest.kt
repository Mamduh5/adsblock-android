package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `matches palworld profile with normalized host ownership`() {
        assertEquals("palworld-gg", SiteProfileRegistry.match("https://palworld.gg/pals").id)
        assertEquals("palworld-gg", SiteProfileRegistry.match("https://www.palworld.gg/map").id)
    }

    @Test
    fun `palworld lookalikes do not match the production profile`() {
        listOf(
            "https://fakepalworld.gg.example.com/",
            "https://palworld.gg.scam.example/",
            "https://notpalworld.gg/",
        ).forEach { url ->
            assertFalse(SiteProfileRegistry.match(url).id == "palworld-gg")
        }
    }

    @Test
    fun `selectable profiles include both production sites`() {
        assertEquals(
            listOf("mangakakalot", "palworld-gg"),
            SiteProfileRegistry.selectableProfiles().map { it.id },
        )
    }
}
