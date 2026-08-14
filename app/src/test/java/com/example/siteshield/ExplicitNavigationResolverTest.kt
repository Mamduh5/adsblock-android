package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitNavigationResolverTest {
    private val engine = GenericBlockerEngine()

    @Test
    fun `known explicit destinations select optimized profiles`() {
        mapOf(
            "https://youtube.com/" to "youtube",
            "https://m.youtube.com/" to "youtube",
            "https://facebook.com/" to "facebook",
            "https://aquareader.org/" to "aquareader",
            "https://palworld.gg/" to "palworld-gg",
            "https://www.mangakakalot.gg/" to "mangakakalot",
        ).forEach { (url, expected) ->
            assertEquals(url, expected, engine.profileForExplicitNavigation(url).id)
        }
    }

    @Test
    fun `unknown and lookalike explicit destinations select generic web`() {
        listOf(
            "https://example.com/",
            "https://youtube.com.evil.test/",
            "https://facebook.com.evil.test/",
            "https://aquareader.org.evil.test/",
        ).forEach { url ->
            assertEquals(url, "generic-web", engine.profileForExplicitNavigation(url).id)
        }
    }

    @Test
    fun `unknown callback while specialized stays specialized but explicit unknown becomes generic`() {
        assertEquals(
            "aquareader",
            engine.profileForTopLevelUrl("https://outside.example/", AquaReaderProfile.profile).id,
        )
        assertEquals(
            "generic-web",
            engine.profileForExplicitNavigation("https://outside.example/").id,
        )
    }

    @Test
    fun `specialized navigation policy cannot be replaced by destination profile`() {
        assertEquals(
            "aquareader",
            engine.profileForNavigationPolicy(
                "https://youtube.com/watch?v=redirect",
                AquaReaderProfile.profile,
                isMainFrame = true,
            ).id,
        )
        assertEquals(
            BlockDecision.Block(BlockReason.OFFSITE_MAIN_FRAME),
            engine.navigationDecision(
                AquaReaderProfile.profile,
                "https://youtube.com/watch?v=redirect",
                "https://aquareader.org/manga/example/",
            ),
        )
    }
}
