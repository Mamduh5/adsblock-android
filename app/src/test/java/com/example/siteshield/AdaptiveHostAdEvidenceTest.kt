package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveHostAdEvidenceTest {
    private val profile = GenericWebProfile.profile
    private val siteUrl = "https://reader.example/chapter/1"
    private val scope = AdaptiveScope(profile.id, "reader.example")

    @Test fun `exact and delimited host roles classify without incidental substrings`() {
        val bid = AdaptiveHostAdClassifier.classify("bid.example.com", AdaptiveResourceKind.OTHER)
        assertEquals(1, bid.evidence.bidderRoleCount)
        assertTrue(bid.strongSeed)

        val weakAd = AdaptiveHostAdClassifier.classify("cdn.publisher-ad.example", AdaptiveResourceKind.OTHER)
        val strongAd = AdaptiveHostAdClassifier.classify("ads.example.com", AdaptiveResourceKind.OTHER)
        val delimitedStrongAd = AdaptiveHostAdClassifier.classify("cdn.publisher-ads.example", AdaptiveResourceKind.OTHER)
        assertEquals(1, weakAd.evidence.adLabelCount)
        assertEquals(2, strongAd.evidence.adLabelCount)
        assertEquals(2, delimitedStrongAd.evidence.adLabelCount)
        val compactClick = AdaptiveHostAdClassifier.classify("js.onclckmn.example", AdaptiveResourceKind.SCRIPT)
        assertEquals(1, compactClick.evidence.clickRoleCount)
        assertEquals(1, compactClick.evidence.loaderRoleCount)
        assertTrue(compactClick.strongSeed)

        listOf("header.example", "load.example", "shadow.example", "address.example",
            "download.example", "admin.example", "shadow-administration.example").forEach { host ->
            assertEquals(host, 0, AdaptiveHostAdClassifier.classify(host, AdaptiveResourceKind.SCRIPT).evidence.total)
        }
    }

    @Test fun `script adds loader role while media remains non loader and protected`() {
        val script = AdaptiveHostAdClassifier.classify("cdn.publisher-ad.example", AdaptiveResourceKind.SCRIPT)
        assertEquals(1, script.evidence.loaderRoleCount)
        assertTrue(script.strongSeed)
        listOf(AdaptiveResourceKind.IMAGE, AdaptiveResourceKind.VIDEO, AdaptiveResourceKind.FONT).forEach { kind ->
            val media = AdaptiveHostAdClassifier.classify("cdn.publisher-ad.example", kind)
            assertEquals(0, media.evidence.loaderRoleCount)
            assertTrue(profile.adaptivePolicy.protectsFromEnforcement("cdn.publisher-ad.example", kind))
        }
    }

    @Test fun `opaque request has host evidence while query protocol evidence is zero`() {
        val url = "https://cdn.publisher-ad.example/pt.js"
        assertEquals(null, AdaptiveProtocolClassifier.classify(url, AdaptiveResourceKind.SCRIPT))
        val host = AdaptiveHostAdClassifier.classify(url.hostFromUrl()!!, AdaptiveResourceKind.SCRIPT)
        assertTrue(host.evidence.adLabelCount > 0)
        assertEquals(1, host.evidence.loaderRoleCount)
    }

    @Test fun `seeded cluster correlates cooperating hosts but not unrelated traffic`() {
        val cluster = AdaptiveSeededAdCluster(windowMs = 100, maxEvents = 64)
        val seed = event("cdn.publisher-ad.example", "doc-a", 0, AdaptiveResourceKind.SCRIPT)
        val bid = event("bid.clicknet.example", "doc-a", 1, AdaptiveResourceKind.OTHER)
        assertTrue(cluster.observe(seed).episodeCredits.isEmpty())
        val joined = cluster.observe(bid)
        assertEquals(setOf("cdn.publisher-ad.example", "bid.clicknet.example"),
            joined.episodeCredits.mapTo(linkedSetOf()) { it.host })

        val benign = event("fonts.googleapis.com", "doc-a", 2, AdaptiveResourceKind.FONT)
        assertFalse(benign.joinEligible)
        assertTrue(cluster.observe(benign).episodeCredits.isEmpty())

        val weak = event("cdn.partner-ad.example", "doc-a", 3, AdaptiveResourceKind.OTHER)
        assertTrue(weak.joinEligible)
        assertEquals(listOf("cdn.partner-ad.example"), cluster.observe(weak).episodeCredits.map { it.host })
    }

    @Test fun `cluster expires and isolates site scope and document generation`() {
        val cluster = AdaptiveSeededAdCluster(windowMs = 50, maxEvents = 64)
        cluster.observe(event("ads.seed.example", "doc-a", 0, AdaptiveResourceKind.SCRIPT))
        assertTrue(cluster.hasActiveSeed(scope, "doc-a", 49))
        assertFalse(cluster.hasActiveSeed(scope, "doc-a", 51))

        cluster.observe(event("ads.seed.example", "doc-a", 60, AdaptiveResourceKind.SCRIPT))
        val otherScope = AdaptiveScope(profile.id, "other.example")
        assertTrue(cluster.observe(event("bid.other.example", "doc-a", 61, AdaptiveResourceKind.OTHER,
            otherScope)).episodeCredits.isEmpty())
        assertTrue(cluster.observe(event("bid.clicknet.example", "doc-b", 62,
            AdaptiveResourceKind.OTHER)).episodeCredits.isEmpty())
    }

    @Test fun `opaque cross-host fixture learns earliest path loader with no other evidence families`() {
        var now = 0L
        val events = mutableListOf<DebugEvent>()
        val controller = controller({ now }, events)
        repeat(3) { generation ->
            now = generation * 10_000L
            val document = "doc-$generation"
            controller.observeRequest(profile, siteUrl, "https://cdn.publisher-ad.example/pt.js",
                false, AdaptiveResourceKind.SCRIPT, document)
            now += 1
            controller.observeRequest(profile, siteUrl, "https://bid.clicknet.example/request",
                false, AdaptiveResourceKind.OTHER, document)
            now += 1
            controller.observeRequest(profile, siteUrl, "https://imp.exchange.example/pixel",
                false, AdaptiveResourceKind.OTHER, document)
            now += 1
            controller.observeRequest(profile, siteUrl, "https://metrics.clicknet.example/event",
                false, AdaptiveResourceKind.OTHER, document)
        }

        val before = controller.records(scope).single {
            it.type == AdaptiveCandidateType.NETWORK_HOST_AD_EVIDENCE &&
                it.host == "cdn.publisher-ad.example"
        }
        assertEquals(AdaptiveCandidateState.CANDIDATE, before.state)
        assertEquals("/pt.js", before.path)
        assertEquals(0, before.staticBlockCount)
        assertEquals(0, before.redirectCorrelationCount)
        assertEquals(0, before.adEvidence.total)
        assertEquals(0, before.protocolEvidence.total)
        assertEquals(3, before.clusterEpisodeCount)

        controller.updateMode(AdaptiveShieldMode.AUTO_SAFE)
        val learned = controller.records(scope).single { it.id == before.id }
        assertEquals(AdaptiveCandidateState.LEARNED, learned.state)
        assertEquals("host-ad-loader+cluster", learned.promotionReason)
        val roundTrip = AdaptiveStateCodec.decode(AdaptiveStateCodec.encode(listOf(learned))).single()
        assertEquals(learned.hostAdEvidence, roundTrip.hostAdEvidence)
        assertEquals(3, roundTrip.clusterEpisodeCount)
        assertTrue(controller.decideRequest(
            profile, siteUrl, "https://cdn.publisher-ad.example/pt.js", AdaptiveResourceKind.SCRIPT, true,
        ) is AdaptiveDecision.Block)
        assertTrue(events.any { it.message == "Adaptive network classifier" &&
            it.detail?.contains("host=cdn.publisher-ad.example") == true &&
            it.detail?.contains("hostEvidence=ad-label+loader") == true &&
            it.detail?.contains("queryEvidence=none") == true &&
            it.detail?.contains("clusterSeed=true") == true })
        controller.close()
    }

    @Test fun `benign third-party repetition never becomes learned or inherits seed`() {
        var now = 0L
        val events = mutableListOf<DebugEvent>()
        val controller = controller({ now }, events)
        val urls = listOf(
            "https://fonts.googleapis.com/css2",
            "https://static.cloudflareinsights.com/beacon.min.js",
            "https://www.googletagmanager.com/gtm.js",
            "https://cdn.example.com/runtime.js",
            "https://api.example.com/data",
        )
        repeat(10) { index ->
            urls.forEach { url ->
                now += 1
                controller.observeRequest(profile, siteUrl, url, false, adaptiveResourceKind(url, emptyMap()), "doc")
            }
        }
        controller.updateMode(AdaptiveShieldMode.AUTO_SAFE)
        val records = controller.records(scope)
        listOf("fonts.googleapis.com", "static.cloudflareinsights.com", "googletagmanager.com").forEach { host ->
            assertTrue(records.filter { it.host == host }.none { it.state == AdaptiveCandidateState.LEARNED })
            assertTrue(records.none { it.host == host && it.type == AdaptiveCandidateType.NETWORK_HOST_AD_EVIDENCE })
        }
        assertTrue(events.any { it.detail?.contains("host=static.cloudflareinsights.com") == true &&
            it.detail?.contains("hostEvidence=none") == true &&
            it.detail?.contains("clusterSeed=false") == true })
        controller.close()
    }

    @Test fun `raw same-document request spam receives one host episode only`() {
        var now = 0L
        val controller = controller({ now }, mutableListOf())
        controller.observeRequest(profile, siteUrl, "https://cdn.publisher-ad.example/pt.js",
            false, AdaptiveResourceKind.SCRIPT, "doc")
        repeat(50) { index ->
            now += 1
            controller.observeRequest(profile, siteUrl, "https://bid.clicknet.example/request/$index",
                false, AdaptiveResourceKind.OTHER, "doc")
        }
        val bidder = controller.records(scope).single {
            it.type == AdaptiveCandidateType.NETWORK_HOST_AD_EVIDENCE && it.host == "bid.clicknet.example"
        }
        assertEquals(1, bidder.clusterEpisodeCount)
        assertEquals(1, bidder.occurrenceCount)
        controller.updateMode(AdaptiveShieldMode.AUTO_SAFE)
        assertFalse(controller.records(scope).single { it.id == bidder.id }.state == AdaptiveCandidateState.LEARNED)
        controller.close()
    }

    @Test fun `intent mismatch near seed gains cluster evidence but intended link does not`() {
        var now = 0L
        val controller = controller({ now }, mutableListOf())
        controller.observeRequest(profile, siteUrl, "https://cdn.publisher-ad.example/pt.js",
            false, AdaptiveResourceKind.SCRIPT, "doc")
        repeat(3) {
            now += 1
            controller.observeNavigation(profile, siteUrl, "https://rotating-popup.example/landing",
                popup = false, blockedBySourcePolicy = false, intentMismatch = true, documentKey = "doc")
        }
        val mismatch = controller.records(scope).single { it.host == "rotating-popup.example" }
        assertEquals(3, mismatch.navigationClusterCorrelationCount)
        controller.updateMode(AdaptiveShieldMode.AUTO_SAFE)
        assertEquals(AdaptiveCandidateState.LEARNED,
            controller.records(scope).single { it.id == mismatch.id }.state)

        val tracker = NavigationIntentTracker({ now })
        val generation = tracker.documentStarted()
        tracker.record(generation, tracker.channelToken(), "external.example", "/article", false)
        val intended = tracker.resolve("https://external.example/article?reader=1", true, false)
        assertTrue(intended.trusted)
        if (!intended.trusted) {
            controller.observeNavigation(profile, siteUrl, "https://external.example/article",
                false, false, true, "doc")
        }
        assertTrue(controller.records(scope).none { it.host == "external.example" })
        controller.close()
    }

    private fun event(
        host: String,
        document: String,
        at: Long,
        kind: AdaptiveResourceKind,
        eventScope: AdaptiveScope = scope,
    ): AdaptiveSeededAdCluster.Event {
        val classification = AdaptiveHostAdClassifier.classify(host, kind)
        return AdaptiveSeededAdCluster.Event(
            scope = eventScope,
            documentKey = document,
            host = host,
            path = "/resource",
            resourceKind = kind,
            hostEvidence = classification.evidence,
            protocolEvidence = AdaptiveProtocolEvidence(),
            strongSeed = classification.strongSeed,
            joinEligible = classification.joinEligible,
            pathScoped = kind == AdaptiveResourceKind.SCRIPT && classification.evidence.loaderRoleCount > 0,
            functionalConflict = false,
            observedAtMs = at,
        )
    }

    private fun controller(clock: () -> Long, events: MutableList<DebugEvent>): AdaptiveShieldController =
        AdaptiveShieldController(
            persistence = object : AdaptiveStatePersistence {
                override fun load(): List<AdaptiveRecord> = emptyList()
                override fun save(records: List<AdaptiveRecord>) = Unit
            },
            initialMode = AdaptiveShieldMode.LEARN,
            profileById = SiteProfileRegistry::byId,
            onEvent = events::add,
            clock = AdaptiveClock(clock),
            persistDelayMs = 60_000,
        )
}
