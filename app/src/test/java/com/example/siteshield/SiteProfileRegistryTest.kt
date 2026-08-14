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

        assertEquals("generic-web", profile.id)
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
    fun `matches aquareader and rejects lookalikes`() {
        assertEquals("aquareader", SiteProfileRegistry.match("https://aquareader.org/manga/").id)
        assertEquals("aquareader", SiteProfileRegistry.match("https://www.aquareader.org/").id)

        listOf(
            "https://aquareader.org.example.com/",
            "https://fake-aquareader.org/",
            "https://aquareader.org.scam.example/",
        ).forEach { url ->
            assertFalse(SiteProfileRegistry.match(url).id == "aquareader")
        }
    }

    @Test
    fun `selectable profiles include all production sites in order`() {
        assertEquals(
            listOf("generic-web", "mangakakalot", "palworld-gg", "aquareader", "youtube", "facebook"),
            SiteProfileRegistry.selectableProfiles().map { it.id },
        )
    }

    @Test
    fun `matches youtube navigation hosts and rejects lookalikes`() {
        listOf(
            "https://youtube.com/",
            "https://www.youtube.com/watch?v=abc",
            "https://m.youtube.com/results?search_query=test",
            "https://youtu.be/abc",
        ).forEach { url ->
            assertEquals(url, "youtube", SiteProfileRegistry.match(url).id)
        }

        listOf(
            "https://youtube.example.com/",
            "https://youtube.com.scam.example/",
            "https://fake-youtube.com/",
            "https://youtu.be.example.com/",
            "https://rr1---sn.example.c.youtube.com/videoplayback",
        ).forEach { url ->
            assertFalse(SiteProfileRegistry.match(url).id == "youtube")
        }
    }

    @Test
    fun `matches facebook locale and mobile hosts and rejects lookalikes`() {
        listOf(
            "https://facebook.com/",
            "https://www.facebook.com/marketplace/",
            "https://m.facebook.com/reel/123",
            "https://th-th.facebook.com/Meta",
        ).forEach { url ->
            assertEquals(url, "facebook", SiteProfileRegistry.match(url).id)
        }

        listOf(
            "https://facebook.example.com/",
            "https://facebook.com.scam.example/",
            "https://fake-facebook.com/",
            "https://facebookcom.example/",
            "https://edge-chat.facebook.com/pull",
            "https://static.facebook.com/app.js",
        ).forEach { url ->
            assertFalse(SiteProfileRegistry.match(url).id == "facebook")
        }
    }
}
