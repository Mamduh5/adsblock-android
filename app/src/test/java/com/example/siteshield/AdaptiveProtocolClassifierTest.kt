package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveProtocolClassifierTest {
    @Test fun `query identifiers are reduced to safe booleans`() {
        val input = "https://ads.example/pop?spot_id=123456&click_id=SECRET123&campaign_type=lq-pop"
        val result = AdaptiveProtocolClassifier.classify(input, AdaptiveResourceKind.SCRIPT)!!
        assertEquals(1, result.evidence.placementCount)
        assertEquals(1, result.evidence.clickCount)
        assertEquals(1, result.evidence.popupCount)
        val diagnostic = result.toString()
        assertFalse(diagnostic.contains("123456"))
        assertFalse(diagnostic.contains("SECRET123"))
        assertFalse(diagnostic.contains("lq-pop"))
        val profile = GenericWebProfile.profile
        val engine = AdaptiveShieldEngine()
        engine.observe(
            AdaptiveProtocolObservation(
                profileId = profile.id,
                host = "ads.example",
                path = result.normalizedPath,
                pageType = PageType.UNKNOWN,
                observedAtMs = 1,
                evidence = result.evidence,
                thirdParty = true,
                pathScoped = true,
                siteScope = "reader.example",
            ),
            profile.adaptivePolicy,
            AdaptiveShieldMode.LEARN,
        )
        val stored = AdaptiveStateCodec.encode(engine.snapshot(2))
        val safeLog = engine.snapshot(2).single().safeDiagnostic()
        listOf("123456", "SECRET123", "lq-pop").forEach { secret ->
            assertFalse(stored.contains(secret))
            assertFalse(safeLog.contains(secret))
        }
    }

    @Test fun `one generic campaign parameter is not protocol evidence`() {
        assertNull(AdaptiveProtocolClassifier.classify("https://cdn.example/app.js?campaign=sale", AdaptiveResourceKind.SCRIPT))
    }

    @Test fun `media resources are protected from protocol classification`() {
        assertNull(AdaptiveProtocolClassifier.classify("https://cdn.example/imp?zoneid=1", AdaptiveResourceKind.IMAGE))
    }

    @Test fun `seeded cluster is cross-host expiring and bounded`() {
        val scope = AdaptiveScope("generic-web", "reader.example")
        val cluster = AdaptiveSeededAdCluster(windowMs = 100, maxEvents = 4)
        fun event(host: String, at: Long, seed: Boolean, eligible: Boolean) = cluster.observe(
            AdaptiveSeededAdCluster.Event(
                scope, "doc", host, "/", AdaptiveResourceKind.OTHER, AdaptiveHostAdEvidence(),
                AdaptiveProtocolEvidence(), seed, eligible, false, false, at,
            ),
        )

        assertTrue(event("ads.example", 0, true, true).episodeCredits.isEmpty())
        assertEquals(2, event("bid.example", 1, true, true).episodeCredits.size)
        assertTrue(event("other.example", 2, false, false).episodeCredits.isEmpty())
        assertFalse(cluster.hasActiveSeed(scope, "doc", 200))
        repeat(8) { event("role-$it.example", 201L + it, true, true) }
        assertEquals(4, cluster.size())
    }

    @Test fun `repetition without independent evidence does not form cluster`() {
        val scope = AdaptiveScope("generic-web", "reader.example")
        val cluster = AdaptiveSeededAdCluster()
        repeat(10) {
            val result = cluster.observe(AdaptiveSeededAdCluster.Event(
                scope, "doc", "analytics.example", "/event", AdaptiveResourceKind.SCRIPT,
                AdaptiveHostAdEvidence(), AdaptiveProtocolEvidence(), false, false, false, false, it.toLong(),
            ))
            assertTrue(result.episodeCredits.isEmpty())
        }
    }

    @Test fun `ArenaScan style fixture learns with no static redirect or DOM evidence`() {
        val profile = GenericWebProfile.profile
        val scope = AdaptiveScope(profile.id, "fixture.example")
        val engine = AdaptiveShieldEngine()
        val urls = listOf(
            "https://exchange.example/loader.js?placement_id=1&ssp=x",
            "https://exchange.example/bid?zoneid=2&auction=abc",
            "https://exchange.example/impression?placement=3&imp=xyz",
            "https://exchange.example/pop?spot_id=4&campaign_type=lq-pop",
            "https://exchange.example/bid?placement_id=5&bid=def",
            "https://exchange.example/pop?zone_id=6&resp_type=popunderAd",
        )
        urls.forEachIndexed { index, url ->
            val classified = AdaptiveProtocolClassifier.classify(url, AdaptiveResourceKind.SCRIPT)!!
            val clustered = if (index >= 2) 1 else 0
            engine.observe(
                AdaptiveProtocolObservation(
                    profileId = profile.id,
                    host = "exchange.example",
                    path = null,
                    pageType = PageType.UNKNOWN,
                    observedAtMs = index.toLong(),
                    evidence = classified.evidence.withCluster(clustered),
                    thirdParty = true,
                    pathScoped = false,
                    siteScope = scope.siteScope,
                ),
                profile.adaptivePolicy,
                AdaptiveShieldMode.LEARN,
            )
        }
        val learnedBefore = engine.snapshot(10).single()
        assertEquals(AdaptiveCandidateState.CANDIDATE, learnedBefore.state)
        assertEquals(0, learnedBefore.staticBlockCount)
        assertEquals(0, learnedBefore.redirectCorrelationCount)
        assertEquals(0, learnedBefore.adEvidence.total)
        engine.reconsider({ profile.adaptivePolicy }, AdaptiveShieldMode.AUTO_SAFE, 11)
        val learned = engine.snapshot(11).single()
        assertEquals(AdaptiveCandidateState.LEARNED, learned.state)
        assertTrue(learned.protocolEvidence.total > 0)
        assertEquals("protocol-auction+popup-cluster", learned.promotionReason)
    }
}
