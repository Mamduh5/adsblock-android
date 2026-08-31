package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveScoringTest {
    private val profile = MangakakalotProfile.profile
    private val sourceUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1"

    @Test
    fun `single popup is observed but never auto promoted`() {
        val engine = AdaptiveShieldEngine()
        val observation = popupObservation(atMs = 1)

        val change = engine.observe(observation, profile.adaptivePolicy, AdaptiveShieldMode.AUTO_SAFE)

        assertNotNull(change)
        assertEquals(AdaptiveCandidateState.OBSERVED, change?.current?.state)
        assertEquals(1, change?.current?.occurrenceCount)
    }

    @Test
    fun `off mode records nothing`() {
        assertNull(
            AdaptiveShieldEngine().observe(
                popupObservation(atMs = 1),
                profile.adaptivePolicy,
                AdaptiveShieldMode.OFF,
            ),
        )
    }

    @Test
    fun `repeated popup becomes candidate in learn and learned only in auto safe`() {
        val engine = AdaptiveShieldEngine()
        repeat(3) { index ->
            engine.observe(
                popupObservation(atMs = index + 1L),
                profile.adaptivePolicy,
                AdaptiveShieldMode.LEARN,
            )
        }

        assertEquals(AdaptiveCandidateState.CANDIDATE, engine.snapshot(4).single().state)

        engine.reconsider({ profile.adaptivePolicy }, AdaptiveShieldMode.AUTO_SAFE, nowMs = 5)
        val learned = engine.snapshot(5).single()
        assertEquals(AdaptiveCandidateState.LEARNED, learned.state)
        assertTrue(learned.confidence >= 90)
    }

    @Test
    fun `generic web external navigation is scoped to its source site`() {
        val observation = AdaptiveObservationFactory.navigation(
                profile = GenericWebProfile.profile,
                sourceUrl = "https://search.example/results",
                targetUrl = "https://normal.example/article",
                popup = false,
                blockedBySourcePolicy = false,
                observedAtMs = 1,
            )
        assertEquals("search.example", observation?.siteScope)
    }

    @Test
    fun `exact first party loader needs six repeats and nearby app script is ignored`() {
        val engine = AdaptiveShieldEngine()
        val loaderUrl = "https://www.mangakakalot.gg/js/ads/new-loader-1234567890abcdef.js?token=secret"
        repeat(5) { index ->
            val observation = requireNotNull(
                AdaptiveObservationFactory.request(
                    profile = profile,
                    pageUrl = sourceUrl,
                    requestUrl = loaderUrl,
                    blockedByStaticRule = false,
                    correlatedWithRedirect = false,
                    functionalEvidence = false,
                    resourceKind = AdaptiveResourceKind.OTHER,
                    observedAtMs = index + 1L,
                ),
            )
            engine.observe(observation, profile.adaptivePolicy, AdaptiveShieldMode.AUTO_SAFE)
        }
        assertEquals(AdaptiveCandidateState.CANDIDATE, engine.snapshot(6).single().state)

        val sixth = requireNotNull(
            AdaptiveObservationFactory.request(
                profile = profile,
                pageUrl = sourceUrl,
                requestUrl = loaderUrl,
                blockedByStaticRule = false,
                correlatedWithRedirect = false,
                functionalEvidence = false,
                resourceKind = AdaptiveResourceKind.OTHER,
                observedAtMs = 6,
            ),
        )
        val promoted = engine.observe(sixth, profile.adaptivePolicy, AdaptiveShieldMode.AUTO_SAFE)
        assertEquals(AdaptiveCandidateState.LEARNED, promoted?.current?.state)
        assertEquals("/js/ads/new-loader-1234567890abcdef.js", promoted?.current?.path)

        val functional = AdaptiveObservationFactory.request(
                profile = profile,
                pageUrl = sourceUrl,
                requestUrl = "https://www.mangakakalot.gg/js/app.js",
                blockedByStaticRule = false,
                correlatedWithRedirect = false,
                functionalEvidence = false,
                resourceKind = AdaptiveResourceKind.OTHER,
                observedAtMs = 7,
            )
        assertNotNull(functional)
        assertEquals(true, functional?.functionalEvidence)
    }

    @Test
    fun `dom candidate is review only even after repeated evidence`() {
        val engine = AdaptiveShieldEngine()
        val domPolicy = profile.adaptivePolicy.copy(
            autoPromoteTypes = profile.adaptivePolicy.autoPromoteTypes + AdaptiveCandidateType.DOM_STRUCTURE,
        )
        repeat(20) { index ->
            engine.observe(
                AdaptiveDomObservation(
                    profileId = profile.id,
                    host = "mangakakalot.gg",
                    path = null,
                    pageType = PageType.CHAPTER_READER,
                    observedAtMs = index + 1L,
                    fingerprint = "stable-ad-root",
                ),
                domPolicy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }

        assertEquals(AdaptiveCandidateState.CANDIDATE, engine.snapshot(30).single().state)
        assertEquals(AdaptiveRiskTier.HIGH_RISK, engine.snapshot(30).single().riskTier)
    }

    @Test
    fun `third party host can promote without redirect correlation when static evidence repeats`() {
        val engine = AdaptiveShieldEngine()
        repeat(5) { index ->
            engine.observe(
                AdaptiveRequestObservation(
                    profileId = profile.id,
                    host = "cdn-unknown.example",
                    path = "/runtime.js",
                    pageType = PageType.CHAPTER_READER,
                    observedAtMs = index + 1L,
                    thirdParty = true,
                    blockedByStaticRule = true,
                    correlatedWithRedirect = false,
                    functionalEvidence = false,
                    loaderPath = false,
                    resourceKind = AdaptiveResourceKind.OTHER,
                ),
                profile.adaptivePolicy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        assertEquals(AdaptiveCandidateState.LEARNED, engine.snapshot(30).single().state)

        repeat(5) { index ->
            engine.observe(
                AdaptiveRequestObservation(
                    profileId = profile.id,
                    host = "correlated-ad.example",
                    path = "/runtime.js",
                    pageType = PageType.CHAPTER_READER,
                    observedAtMs = 40 + index.toLong(),
                    thirdParty = true,
                    blockedByStaticRule = false,
                    correlatedWithRedirect = true,
                    functionalEvidence = false,
                    loaderPath = false,
                    resourceKind = AdaptiveResourceKind.OTHER,
                ),
                profile.adaptivePolicy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        assertEquals(
            AdaptiveCandidateState.LEARNED,
            engine.snapshot(50).first { it.host == "correlated-ad.example" }.state,
        )
    }

    private fun popupObservation(atMs: Long): AdaptiveNavigationObservation = requireNotNull(
        AdaptiveObservationFactory.navigation(
            profile = profile,
            sourceUrl = sourceUrl,
            targetUrl = "https://rotating-ad.example/landing?secret=hidden",
            popup = true,
            blockedBySourcePolicy = true,
            observedAtMs = atMs,
        ),
    )
}
