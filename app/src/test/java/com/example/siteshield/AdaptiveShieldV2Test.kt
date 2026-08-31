package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveShieldV2Test {
    private val profile = GenericWebProfile.profile
    private val policy = profile.adaptivePolicy
    private val siteA = "https://site-a.example/article"
    private val siteB = "https://site-b.example/home"
    private val learnedHost = "ads.vendor.example"

    @Test
    fun `generic sites produce independent adaptive scopes and rule ids`() {
        val a = request(siteA, learnedHost, atMs = 1)
        val b = request(siteB, learnedHost, atMs = 2)
        val engine = AdaptiveShieldEngine()

        val aRecord = engine.observe(a, policy, AdaptiveShieldMode.LEARN)?.current
        val bRecord = engine.observe(b, policy, AdaptiveShieldMode.LEARN)?.current

        assertEquals("site-a.example", a.siteScope)
        assertEquals("site-b.example", b.siteScope)
        assertNotEquals(aRecord?.id, bRecord?.id)
    }

    @Test
    fun `evidence learned on site A blocks A but not site B`() {
        val engine = learnedThirdPartyEngine(siteA)

        assertTrue(decideRequest(engine, siteA) is AdaptiveDecision.Block)
        assertEquals(AdaptiveDecision.Allow, decideRequest(engine, siteB))
    }

    @Test
    fun `generic first party request is functional rather than third party`() {
        val observation = request(siteA, "site-a.example", atMs = 1)

        assertFalse(observation.thirdParty)
        assertTrue(observation.functionalEvidence)
        assertEquals("site-a.example", observation.siteScope)
    }

    @Test
    fun `learn mode never enforces an eligible learned resource rule`() {
        val engine = learnedThirdPartyEngine(siteA)

        val decision = engine.decideRequest(
            AdaptiveScope(profile.id, "site-a.example"),
            policy,
            "https://$learnedHost/runtime.js",
            AdaptiveResourceKind.OTHER,
            userInitiated = false,
            blockerEnabled = true,
            mode = AdaptiveShieldMode.LEARN,
            nowMs = 30,
        )

        assertEquals(AdaptiveDecision.Allow, decision)
    }

    @Test
    fun `auto safe enforces eligible learned resource rule`() {
        assertTrue(decideRequest(learnedThirdPartyEngine(siteA), siteA) is AdaptiveDecision.Block)
    }

    @Test
    fun `learned offsite redirect uses navigation decision path`() {
        val engine = learnedNavigationEngine()
        val scope = AdaptiveScope(profile.id, "site-a.example")

        assertTrue(
            engine.decideNavigation(
                scope,
                policy,
                "https://redirect.vendor.example/landing",
                userInitiated = false,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 10,
            ) is AdaptiveDecision.Block,
        )
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decideRequest(
                scope,
                policy,
                "https://redirect.vendor.example/payload.js",
                AdaptiveResourceKind.OTHER,
                userInitiated = false,
                blockerEnabled = true,
                mode = AdaptiveShieldMode.AUTO_SAFE,
                nowMs = 10,
            ),
        )
    }

    @Test
    fun `explicit user navigation bypasses learned redirect rule`() {
        val decision = learnedNavigationEngine().decideNavigation(
            AdaptiveScope(profile.id, "site-a.example"),
            policy,
            "https://redirect.vendor.example/landing",
            userInitiated = true,
            blockerEnabled = true,
            mode = AdaptiveShieldMode.AUTO_SAFE,
            nowMs = 10,
        )

        assertEquals(AdaptiveDecision.Allow, decision)
    }

    @Test
    fun `login and session navigation is excluded from adaptive learning`() {
        assertEquals(
            null,
            AdaptiveObservationFactory.navigation(
                profile,
                siteA,
                "https://identity.vendor.example/oauth/authorize",
                popup = false,
                blockedBySourcePolicy = false,
                observedAtMs = 1,
            ),
        )
    }

    @Test
    fun `third party rule can promote from repeated static evidence without redirect correlation`() {
        val record = learnedThirdPartyEngine(siteA).snapshot(30).single()

        assertEquals(AdaptiveCandidateState.LEARNED, record.state)
        assertEquals(0, record.redirectCorrelationCount)
    }

    @Test
    fun `redirect correlation increases confidence`() {
        val plain = AdaptiveShieldEngine()
        val correlated = AdaptiveShieldEngine()
        repeat(5) { index ->
            plain.observe(request(siteA, learnedHost, index + 1L), policy, AdaptiveShieldMode.LEARN)
            correlated.observe(
                request(siteA, learnedHost, index + 1L, correlated = true),
                policy,
                AdaptiveShieldMode.LEARN,
            )
        }

        assertTrue(correlated.snapshot(10).single().confidence > plain.snapshot(10).single().confidence)
    }

    @Test
    fun `functional evidence prevents unsafe promotion`() {
        val engine = AdaptiveShieldEngine()
        repeat(30) { index ->
            engine.observe(
                request(siteA, learnedHost, index + 1L, staticBlocked = true, functional = true),
                policy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }

        val record = engine.snapshot(40).single()
        assertTrue(record.functionalEvidenceCount > 0)
        assertNotEquals(AdaptiveCandidateState.LEARNED, record.state)
    }

    @Test
    fun `protected media is observed but never promoted or enforced`() {
        val engine = AdaptiveShieldEngine()
        repeat(30) { index ->
            val observation = requireNotNull(
                AdaptiveObservationFactory.request(
                    profile,
                    siteA,
                    "https://images.vendor.example/page-$index.jpg",
                    blockedByStaticRule = true,
                    correlatedWithRedirect = true,
                    functionalEvidence = true,
                    resourceKind = AdaptiveResourceKind.IMAGE,
                    observedAtMs = index + 1L,
                ),
            )
            engine.observe(observation, policy, AdaptiveShieldMode.AUTO_SAFE)
        }

        assertTrue(engine.snapshot(40).all { it.functionalEvidenceCount > 0 })
        assertTrue(engine.snapshot(40).none { it.state == AdaptiveCandidateState.LEARNED })
    }

    @Test
    fun `legacy corrupt and generic global persistence fail safely`() {
        assertTrue(AdaptiveStateCodec.decode("v1\nlegacy-global-record").isEmpty())
        assertTrue(AdaptiveStateCodec.decode("v2\nbroken").isEmpty())
        val global = AdaptiveRecord(
            id = "adaptive:generic-web:third_party_request_host:$learnedHost:host",
            profileId = profile.id,
            type = AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST,
            riskTier = AdaptiveRiskTier.MEDIUM_RISK,
            host = learnedHost,
            path = null,
            state = AdaptiveCandidateState.LEARNED,
            occurrenceCount = 30,
            popupCount = 0,
            sourcePolicyBlockCount = 0,
            staticBlockCount = 30,
            thirdPartyCount = 30,
            redirectCorrelationCount = 0,
            functionalEvidenceCount = 0,
            firstSeenAtMs = 1,
            lastSeenAtMs = 30,
            learnedAtMs = 30,
            rejectedAtMs = null,
            score = 1_000,
            confidence = 100,
        )

        assertTrue(AdaptiveShieldEngine(listOf(global)).snapshot(31).isEmpty())
    }

    @Test
    fun `forget removes only one generic adaptive scope`() {
        val engine = AdaptiveShieldEngine()
        engine.observe(request(siteA, learnedHost, 1), policy, AdaptiveShieldMode.LEARN)
        engine.observe(request(siteB, learnedHost, 2), policy, AdaptiveShieldMode.LEARN)

        engine.forget(AdaptiveScope(profile.id, "site-a.example"))

        val remaining = engine.snapshot(3)
        assertEquals(1, remaining.size)
        assertEquals("site-b.example", remaining.single().siteScope)
    }

    private fun learnedThirdPartyEngine(pageUrl: String): AdaptiveShieldEngine =
        AdaptiveShieldEngine().also { engine ->
            repeat(5) { index ->
                engine.observe(
                    request(pageUrl, learnedHost, index + 1L, staticBlocked = true),
                    policy,
                    AdaptiveShieldMode.AUTO_SAFE,
                )
            }
        }

    private fun learnedNavigationEngine(): AdaptiveShieldEngine = AdaptiveShieldEngine().also { engine ->
        repeat(3) { index ->
            val observation = requireNotNull(
                AdaptiveObservationFactory.navigation(
                    profile,
                    siteA,
                    "https://redirect.vendor.example/landing",
                    popup = true,
                    blockedBySourcePolicy = false,
                    observedAtMs = index + 1L,
                ),
            )
            engine.observe(observation, policy, AdaptiveShieldMode.AUTO_SAFE)
        }
    }

    private fun decideRequest(engine: AdaptiveShieldEngine, pageUrl: String): AdaptiveDecision =
        engine.decideRequest(
            requireNotNull(adaptiveScope(profile, pageUrl)),
            policy,
            "https://$learnedHost/runtime.js",
            AdaptiveResourceKind.OTHER,
            userInitiated = false,
            blockerEnabled = true,
            mode = AdaptiveShieldMode.AUTO_SAFE,
            nowMs = 30,
        )

    private fun request(
        pageUrl: String,
        host: String,
        atMs: Long,
        staticBlocked: Boolean = false,
        correlated: Boolean = false,
        functional: Boolean = false,
    ): AdaptiveRequestObservation = requireNotNull(
        AdaptiveObservationFactory.request(
            profile,
            pageUrl,
            "https://$host/runtime.js",
            blockedByStaticRule = staticBlocked,
            correlatedWithRedirect = correlated,
            functionalEvidence = functional,
            resourceKind = AdaptiveResourceKind.OTHER,
            observedAtMs = atMs,
        ),
    )
}
