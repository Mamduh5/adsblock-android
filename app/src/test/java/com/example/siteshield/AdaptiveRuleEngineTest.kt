package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveRuleEngineTest {
    private val profile = MangakakalotProfile.profile
    private val sourceUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1"

    @Test
    fun `learned host is profile scoped and blocker off disables adaptive enforcement`() {
        val engine = learnedRedirectEngine()
        val target = "https://rotating-ad.example/payload.js"

        assertEquals(
            AdaptiveDecision.Block(
                ruleId = "adaptive:mangakakalot:offsite_redirect_host:rotating-ad.example:host",
                confidence = 100,
                type = AdaptiveCandidateType.OFFSITE_REDIRECT_HOST,
            ),
            engine.decide(
                profile,
                target,
                PageType.CHAPTER_READER,
                AdaptiveResourceKind.OTHER,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 10,
            ),
        )
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decide(
                GenericWebProfile.profile,
                target,
                PageType.UNKNOWN,
                AdaptiveResourceKind.OTHER,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 10,
            ),
        )
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decide(
                profile,
                target,
                PageType.CHAPTER_READER,
                AdaptiveResourceKind.OTHER,
                blockerEnabled = false,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 10,
            ),
        )
    }

    @Test
    fun `learn mode never enforces an already learned rule`() {
        val engine = learnedRedirectEngine()

        assertEquals(
            AdaptiveDecision.Allow,
            engine.decide(
                profile,
                "https://rotating-ad.example/payload.js",
                PageType.CHAPTER_READER,
                AdaptiveResourceKind.OTHER,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.LEARN,
                nowMs = 10,
            ),
        )
    }

    @Test
    fun `protected media and profile functional hosts cannot become adaptive blocks`() {
        val mangakakalotEngine = learnedRedirectEngine("storage.waitst.com")
        assertEquals(
            AdaptiveDecision.Allow,
            mangakakalotEngine.decide(
                profile,
                "https://storage.waitst.com/chapter/page-1.jpg",
                PageType.CHAPTER_READER,
                AdaptiveResourceKind.IMAGE,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 10,
            ),
        )

        val youtubeObservation = AdaptiveRequestObservation(
            profileId = YouTubeProfile.profile.id,
            host = "r1.googlevideo.com",
            path = "/videoplayback",
            pageType = PageType.VIDEO_WATCH,
            observedAtMs = 1,
            thirdParty = true,
            blockedByStaticRule = true,
            correlatedWithRedirect = true,
            functionalEvidence = false,
            loaderPath = false,
            resourceKind = AdaptiveResourceKind.VIDEO,
        )
        assertNull(
            AdaptiveShieldEngine().observe(
                youtubeObservation,
                YouTubeProfile.profile.adaptivePolicy,
                AdaptiveShieldMode.AUTO_SAFE,
            ),
        )
    }

    @Test
    fun `palworld and facebook observation only policies cannot enforce seeded learned data`() {
        listOf(PalworldGgProfile.profile, FacebookProfile.profile).forEach { protectedProfile ->
            val seeded = learnedRecord(protectedProfile.id, "assets.example")
            val engine = AdaptiveShieldEngine(listOf(seeded))
            assertEquals(
                AdaptiveDecision.Allow,
                engine.decide(
                    protectedProfile,
                    "https://assets.example/runtime.js",
                    PageType.UNKNOWN,
                    AdaptiveResourceKind.OTHER,
                    blockerEnabled = true,
                    mode = AdaptiveShieldMode.AUTO_SAFE,
                    nowMs = 2,
                ),
            )
        }
    }

    @Test
    fun `url normalization omits query and generalizes only stable identifier segments`() {
        val observation = AdaptiveObservationFactory.request(
            profile = profile,
            pageUrl = sourceUrl,
            requestUrl = "https://www.mangakakalot.gg/js/ads/12345/abcdef1234567890.js?token=SECRET&id=999",
            blockedByStaticRule = false,
            correlatedWithRedirect = false,
            functionalEvidence = false,
            resourceKind = AdaptiveResourceKind.OTHER,
            observedAtMs = 1,
        )

        assertEquals("/js/ads/{numeric}/abcdef1234567890.js", observation?.path)
    }

    private fun learnedRedirectEngine(host: String = "rotating-ad.example"): AdaptiveShieldEngine {
        val engine = AdaptiveShieldEngine()
        repeat(3) { index ->
            engine.observe(
                requireNotNull(
                    AdaptiveObservationFactory.navigation(
                        profile,
                        sourceUrl,
                        "https://$host/landing",
                        popup = true,
                        blockedBySourcePolicy = true,
                        observedAtMs = index + 1L,
                    ),
                ),
                profile.adaptivePolicy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        return engine
    }

    private fun learnedRecord(profileId: String, host: String): AdaptiveRecord = AdaptiveRecord(
        id = "adaptive:$profileId:offsite_redirect_host:$host:host",
        profileId = profileId,
        type = AdaptiveCandidateType.OFFSITE_REDIRECT_HOST,
        riskTier = AdaptiveRiskTier.LOW_RISK,
        host = host,
        path = null,
        state = AdaptiveCandidateState.LEARNED,
        occurrenceCount = 3,
        popupCount = 3,
        sourcePolicyBlockCount = 3,
        staticBlockCount = 0,
        thirdPartyCount = 0,
        redirectCorrelationCount = 0,
        functionalEvidenceCount = 0,
        firstSeenAtMs = 1,
        lastSeenAtMs = 1,
        learnedAtMs = 1,
        rejectedAtMs = null,
        score = 210,
        confidence = 100,
    )
}
