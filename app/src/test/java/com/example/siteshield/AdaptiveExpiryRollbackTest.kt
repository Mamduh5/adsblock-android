package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveExpiryRollbackTest {
    private val profile = MangakakalotProfile.profile
    private val policy = profile.adaptivePolicy

    @Test
    fun `learned rule becomes dormant retires and reactivates deterministically`() {
        val config = AdaptiveLearningConfig(
            candidateExpiryMs = 100,
            learnedDormancyMs = 200,
            learnedRetirementMs = 300,
        )
        val engine = AdaptiveShieldEngine(config = config)
        repeat(3) { index -> engine.observe(popup(index + 1L), policy, AdaptiveShieldMode.AUTO_SAFE) }

        assertEquals(AdaptiveCandidateState.LEARNED, engine.snapshot(100).single().state)
        assertEquals(AdaptiveCandidateState.DORMANT, engine.snapshot(220).single().state)

        engine.observe(popup(230), policy, AdaptiveShieldMode.AUTO_SAFE)
        assertEquals(AdaptiveCandidateState.LEARNED, engine.snapshot(230).single().state)
        assertTrue(engine.snapshot(600).isEmpty())
    }

    @Test
    fun `stale unlearned candidate expires`() {
        val config = AdaptiveLearningConfig(
            candidateExpiryMs = 100,
            learnedDormancyMs = 200,
            learnedRetirementMs = 300,
        )
        val engine = AdaptiveShieldEngine(config = config)
        engine.observe(popup(1), policy, AdaptiveShieldMode.LEARN)

        assertTrue(engine.snapshot(102).isEmpty())
    }

    @Test
    fun `catastrophic health failure rejects only recently enforced adaptive rule`() {
        val engine = AdaptiveShieldEngine()
        repeat(3) { index -> engine.observe(popup(index + 1L), policy, AdaptiveShieldMode.AUTO_SAFE) }
        engine.decideNavigation(
            AdaptiveScope(profile.id),
            profile.adaptivePolicy,
            "https://rotating-ad.example/payload.js",
            userInitiated = false,
            blockerEnabled = true,
            mode = AdaptiveShieldMode.AUTO_SAFE,
            nowMs = 10,
        )

        val rejected = engine.reportPageHealth(
            AdaptivePageHealth(
                profileId = profile.id,
                pageType = PageType.CHAPTER_READER,
                healthy = false,
                readerContainerPresent = true,
                chapterImageCount = 0,
                chapterNavigationPresent = true,
            ),
            nowMs = 11,
        )

        assertEquals(1, rejected.size)
        assertEquals(AdaptiveCandidateState.REJECTED, engine.snapshot(11).single().state)
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decideNavigation(
                AdaptiveScope(profile.id),
                profile.adaptivePolicy,
                "https://rotating-ad.example/payload.js",
                userInitiated = false,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 12,
            ),
        )
    }

    @Test
    fun `health failure without adaptive enforcement cannot remove static policy`() {
        val engine = AdaptiveShieldEngine()
        val rejected = engine.reportPageHealth(
            AdaptivePageHealth(profile.id, PageType.CHAPTER_READER, healthy = false),
            nowMs = 1,
        )

        assertTrue(rejected.isEmpty())
        assertEquals(
            BlockDecision.Block(BlockReason.REQUEST_RULE, "oundhertobeconsist-floater"),
            GenericBlockerEngine().resourceDecision(
                profile,
                "https://oundhertobeconsist.org/floater",
                "https://www.mangakakalot.gg/chapter/example/chapter-1",
            ),
        )
    }

    private fun popup(atMs: Long): AdaptiveNavigationObservation = AdaptiveNavigationObservation(
        profileId = profile.id,
        host = "rotating-ad.example",
        pageType = PageType.CHAPTER_READER,
        observedAtMs = atMs,
        popup = true,
        blockedBySourcePolicy = true,
    )
}
