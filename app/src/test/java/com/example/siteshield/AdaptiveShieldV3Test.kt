package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveShieldV3Test {
    private val profile = GenericWebProfile.profile
    private val policy = profile.adaptivePolicy
    private val siteA = "https://site-a.example/article"
    private val scopeA = AdaptiveScope(profile.id, "site-a.example")

    @Test
    fun `explicit ad slot produces strong evidence while unrelated data attributes do not`() {
        val explicit = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                explicitSlotMarker = true,
                structuralAdContext = true,
                iframeUrl = "https://ads.example/render/42",
            ),
        )
        val unrelated = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(structuralAdContext = true, iframeUrl = "https://video.example/embed/42"),
        )

        assertTrue(explicit.single().explicitAdSlot)
        assertTrue(explicit.single().iframeAssociation)
        assertTrue(unrelated.isEmpty())
    }

    @Test
    fun `sponsored attribution requires short exact text and ad structure`() {
        val sponsored = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                shortAttribution = "Sponsored",
                structuralAdContext = true,
                iframeUrl = "https://native-ads.example/card/7",
            ),
        )
        val article = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                shortAttribution = "Sponsored content policy",
                structuralAdContext = true,
                iframeUrl = "https://publisher.example/document",
            ),
        )

        assertTrue(sponsored.single().sponsoredAttribution)
        assertTrue(article.isEmpty())
    }

    @Test
    fun `ad iframe is correlated but a normal video iframe is not`() {
        val ad = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                explicitSlotMarker = true,
                structuralAdContext = true,
                iframeUrl = "https://frame.ads.example/safeframe/1",
            ),
        )
        val video = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                structuralAdContext = true,
                iframeUrl = "https://video.example/embed/1",
            ),
        )

        assertEquals("frame.ads.example", ad.single().host)
        assertTrue(ad.single().iframeAssociation)
        assertTrue(video.isEmpty())
    }

    @Test
    fun `sticky layout alone is insufficient while sticky ad structure is evidence`() {
        val stickyOnly = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                fixedOrSticky = true,
                highZIndex = true,
                viewportCoverage = 0.5,
                structuralAdContext = true,
                iframeUrl = "https://widgets.example/dialog",
            ),
        )
        val stickyAd = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                shortAttribution = "Ad",
                structuralAdContext = true,
                iframeUrl = "https://ads.example/anchor",
                fixedOrSticky = true,
                highZIndex = true,
                viewportCoverage = 0.2,
            ),
        )

        assertTrue(stickyOnly.isEmpty())
        assertTrue(stickyAd.single().overlayLayout)
    }

    @Test
    fun `repeated unknown loader becomes eligible without static or redirect evidence`() {
        val engine = AdaptiveShieldEngine()
        repeat(3) { index ->
            engine.observe(loaderObservation(index + 1L), policy, AdaptiveShieldMode.LEARN)
        }
        val candidate = engine.snapshot(4).single()
        assertEquals(0, candidate.staticBlockCount)
        assertEquals(0, candidate.redirectCorrelationCount)
        assertNotEquals(AdaptiveCandidateState.LEARNED, candidate.state)

        engine.reconsider({ policy }, AdaptiveShieldMode.AUTO_SAFE, 5)
        val learned = engine.snapshot(6).single()
        assertEquals(AdaptiveCandidateState.LEARNED, learned.state)
        assertEquals("repeated-loader+dom-ad", learned.promotionReason)
    }

    @Test
    fun `raw third party repetition alone remains insufficient`() {
        val engine = AdaptiveShieldEngine()
        repeat(30) { index ->
            engine.observe(rawRequest(index + 1L), policy, AdaptiveShieldMode.AUTO_SAFE)
        }
        assertTrue(engine.snapshot(31).none { it.state == AdaptiveCandidateState.LEARNED })
    }

    @Test
    fun `static and redirect evidence still increase ad confidence`() {
        val plain = AdaptiveShieldEngine()
        val static = AdaptiveShieldEngine()
        val redirect = AdaptiveShieldEngine()
        plain.observe(loaderObservation(1), policy, AdaptiveShieldMode.LEARN)
        static.observe(loaderObservation(1, staticBlocked = true), policy, AdaptiveShieldMode.LEARN)
        redirect.observe(loaderObservation(1, redirected = true), policy, AdaptiveShieldMode.LEARN)

        val base = plain.snapshot(2).single().confidence
        assertTrue(static.snapshot(2).single().confidence > base)
        assertTrue(redirect.snapshot(2).single().confidence > base)
    }

    @Test
    fun `media observation is not host functional evidence and remains non enforceable`() {
        val image = requireNotNull(
            AdaptiveObservationFactory.request(
                profile,
                siteA,
                "https://mixed.example/banner.jpg",
                blockedByStaticRule = true,
                correlatedWithRedirect = true,
                functionalEvidence = false,
                resourceKind = AdaptiveResourceKind.IMAGE,
                observedAtMs = 1,
            ),
        )
        assertFalse(image.functionalEvidence)

        val engine = learnedLoaderEngine()
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decideRequest(
                scopeA,
                policy,
                "https://unknown-ads.example/assets/ad-loader.js",
                AdaptiveResourceKind.IMAGE,
                false,
                true,
                AdaptiveShieldMode.AUTO_SAFE,
                10,
            ),
        )
    }

    @Test
    fun `first party ad path can learn but broad first party host remains allowed`() {
        val engine = AdaptiveShieldEngine()
        repeat(4) { index ->
            engine.observe(
                loaderObservation(
                    atMs = index + 1L,
                    host = "site-a.example",
                    path = "/assets/ad-loader.js",
                ),
                policy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        assertEquals(AdaptiveCandidateState.LEARNED, engine.snapshot(5).single().state)
        assertTrue(
            engine.decideRequest(
                scopeA, policy, "https://site-a.example/assets/ad-loader.js", AdaptiveResourceKind.OTHER,
                false, true, AdaptiveShieldMode.AUTO_SAFE, 6,
            ) is AdaptiveDecision.Block,
        )
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decideRequest(
                scopeA, policy, "https://site-a.example/assets/app.js", AdaptiveResourceKind.OTHER,
                false, true, AdaptiveShieldMode.AUTO_SAFE, 6,
            ),
        )
    }

    @Test
    fun `shared CDN learned rule is path scoped`() {
        val engine = learnedLoaderEngine(host = "shared-cdn.example", path = "/ads/loader.js")
        assertTrue(engine.snapshot(10).single().pathScoped)
        assertTrue(decide(engine, "https://shared-cdn.example/ads/loader.js") is AdaptiveDecision.Block)
        assertEquals(AdaptiveDecision.Allow, decide(engine, "https://shared-cdn.example/app/runtime.js"))
    }

    @Test
    fun `login conflict and explicit user navigation remain protected`() {
        val engine = AdaptiveShieldEngine()
        repeat(10) { index ->
            engine.observe(
                loaderObservation(index + 1L, path = "/oauth/session.js", functionalConflict = true),
                policy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        assertTrue(engine.snapshot(11).none { it.state == AdaptiveCandidateState.LEARNED })
        assertEquals(
            AdaptiveDecision.Allow,
            learnedLoaderEngine().decideRequest(
                scopeA, policy, "https://unknown-ads.example/assets/ad-loader.js",
                AdaptiveResourceKind.OTHER, true, true, AdaptiveShieldMode.AUTO_SAFE, 10,
            ),
        )
    }

    @Test
    fun `generic site scopes remain isolated`() {
        val engine = learnedLoaderEngine()
        assertTrue(decide(engine, "https://unknown-ads.example/assets/ad-loader.js") is AdaptiveDecision.Block)
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decideRequest(
                AdaptiveScope(profile.id, "site-b.example"), policy,
                "https://unknown-ads.example/assets/ad-loader.js", AdaptiveResourceKind.OTHER,
                false, true, AdaptiveShieldMode.AUTO_SAFE, 10,
            ),
        )
    }

    @Test
    fun `learn never blocks and auto safe reconsideration blocks next request`() {
        val engine = AdaptiveShieldEngine()
        repeat(3) { index -> engine.observe(loaderObservation(index + 1L), policy, AdaptiveShieldMode.LEARN) }
        assertEquals(
            AdaptiveDecision.Allow,
            engine.decideRequest(
                scopeA, policy, "https://unknown-ads.example/assets/ad-loader.js",
                AdaptiveResourceKind.OTHER, false, true, AdaptiveShieldMode.LEARN, 4,
            ),
        )
        engine.reconsider({ policy }, AdaptiveShieldMode.AUTO_SAFE, 5)
        assertTrue(decide(engine, "https://unknown-ads.example/assets/ad-loader.js") is AdaptiveDecision.Block)
    }

    @Test
    fun `weak native candidate remains non enforced`() {
        val engine = AdaptiveShieldEngine()
        repeat(10) { index ->
            engine.observe(
                loaderObservation(
                    atMs = index + 1L,
                    evidence = AdaptiveAdEvidence(sponsoredAttributionCount = 1),
                ),
                policy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        assertTrue(engine.snapshot(11).none { it.state == AdaptiveCandidateState.LEARNED })
    }

    @Test
    fun `v3 records round trip and corrupt evidence fails safely`() {
        val record = learnedLoaderEngine().snapshot(10).single()
        val decoded = AdaptiveStateCodec.decode(AdaptiveStateCodec.encode(listOf(record)))
        assertEquals(listOf(record), decoded)
        assertTrue(AdaptiveStateCodec.decode("v3\nbroken\tevidence").isEmpty())
    }

    @Test
    fun `v2 migration preserves scope without inventing v3 evidence`() {
        val id = "adaptive:generic-web:site=site-a.example:third_party_request_host:legacy.example:host"
        val v2 = listOf(
            id, profile.id, AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST.name,
            AdaptiveRiskTier.MEDIUM_RISK.name, "legacy.example", "", AdaptiveCandidateState.CANDIDATE.name,
            "5", "0", "0", "3", "5", "0", "0", "1", "5", "-1", "-1", "145", "72",
            "site-a.example",
        ).joinToString("\t", prefix = "v2\n")

        val migrated = AdaptiveStateCodec.decode(v2).single()
        assertEquals("site-a.example", migrated.siteScope)
        assertEquals(0, migrated.adEvidence.total)
        assertFalse(migrated.pathScoped)
    }

    @Test
    fun `compact DOM report rejects missing structure and unsafe shapes`() {
        assertTrue(parseAdaptiveDomAdReports("A3\t5\tIFRAME\tads.example\t/render\ttrue\ttrue").isNotEmpty())
        assertTrue(parseAdaptiveDomAdReports("A3\t4\tIFRAME\tvideo.example\t/embed\ttrue\ttrue").isEmpty())
        assertTrue(parseAdaptiveDomAdReports("A3\t5\tIFRAME\tads.example\t/render\ttrue\tfalse").isEmpty())
        assertTrue(parseAdaptiveDomAdReports("A3\t999\tIFRAME\tads.example\t/render\ttrue\ttrue").isEmpty())
    }

    @Test
    fun `controller requires bounded same-scope network correlation before DOM promotion`() {
        var nowMs = 1L
        val persistence = object : AdaptiveStatePersistence {
            var records: List<AdaptiveRecord> = emptyList()
            override fun load(): List<AdaptiveRecord> = records
            override fun save(records: List<AdaptiveRecord>) {
                this.records = records
            }
        }
        val controller = AdaptiveShieldController(
            persistence = persistence,
            initialMode = AdaptiveShieldMode.LEARN,
            profileById = { profile },
            onEvent = {},
            clock = AdaptiveClock { nowMs },
            persistDelayMs = 1_000_000,
        )
        val report = AdaptiveDomAdReport(
            role = AdaptiveAdResourceRole.LOADER,
            host = "unknown-ads.example",
            path = "/assets/ad-loader.js",
            explicitAdSlot = true,
            sponsoredAttribution = false,
            iframeAssociation = false,
            overlayLayout = false,
            pathScoped = true,
        )
        try {
            controller.observeDomAdEvidence(profile, siteA, listOf(report))
            assertTrue(controller.records(scopeA).isEmpty())
            repeat(3) {
                controller.observeRequest(
                    profile, siteA, "https://unknown-ads.example/assets/ad-loader.js",
                    blockedByStaticRule = false, resourceKind = AdaptiveResourceKind.OTHER,
                )
                controller.observeDomAdEvidence(profile, siteA, listOf(report))
                nowMs += 1
            }
            controller.updateMode(AdaptiveShieldMode.AUTO_SAFE)
            val adRecord = controller.records(scopeA).single { it.type == AdaptiveCandidateType.AD_RESOURCE }
            assertEquals(AdaptiveCandidateState.LEARNED, adRecord.state)
            assertEquals(0, adRecord.staticBlockCount)
            assertEquals(0, adRecord.redirectCorrelationCount)
            assertTrue(
                controller.decideRequest(
                    profile, siteA, "https://unknown-ads.example/assets/ad-loader.js",
                    AdaptiveResourceKind.OTHER, blockerEnabled = true,
                ) is AdaptiveDecision.Block,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun `controller infers only one unambiguous script from explicit slot and correlated iframe`() {
        var nowMs = 1L
        val persistence = object : AdaptiveStatePersistence {
            override fun load(): List<AdaptiveRecord> = emptyList()
            override fun save(records: List<AdaptiveRecord>) = Unit
        }
        val controller = AdaptiveShieldController(
            persistence, AdaptiveShieldMode.LEARN, { profile }, {}, AdaptiveClock { nowMs }, 1_000_000,
        )
        val structure = AdaptiveDomAdReport(
            AdaptiveAdResourceRole.STRUCTURE, "site-a.example", "/", true, false, false, false, false,
        )
        val frame = AdaptiveDomAdReport(
            AdaptiveAdResourceRole.IFRAME, "frames.example", "/render/{numeric}", true, false, true, false, true,
        )
        try {
            repeat(3) {
                controller.observeRequest(
                    profile, siteA, "https://unknown-ads.example/adloader.js",
                    false, AdaptiveResourceKind.OTHER,
                )
                controller.observeRequest(
                    profile, siteA, "https://frames.example/render/${it + 1}",
                    false, AdaptiveResourceKind.OTHER,
                )
                controller.observeDomAdEvidence(profile, siteA, listOf(structure, frame))
                nowMs += 1
            }
            controller.updateMode(AdaptiveShieldMode.AUTO_SAFE)
            val inferred = controller.records(scopeA).single {
                it.type == AdaptiveCandidateType.AD_RESOURCE && it.host == "unknown-ads.example"
            }
            assertEquals(AdaptiveCandidateState.LEARNED, inferred.state)
            assertEquals("repeated-loader+dom-ad", inferred.promotionReason)
            assertEquals(0, inferred.staticBlockCount)
            assertEquals(0, inferred.redirectCorrelationCount)
        } finally {
            controller.close()
        }
    }

    private fun learnedLoaderEngine(
        host: String = "unknown-ads.example",
        path: String = "/assets/ad-loader.js",
    ): AdaptiveShieldEngine = AdaptiveShieldEngine().also { engine ->
        repeat(3) { index ->
            engine.observe(loaderObservation(index + 1L, host = host, path = path), policy, AdaptiveShieldMode.AUTO_SAFE)
        }
    }

    private fun loaderObservation(
        atMs: Long,
        host: String = "unknown-ads.example",
        path: String = "/assets/ad-loader.js",
        evidence: AdaptiveAdEvidence = AdaptiveAdEvidence(
            explicitAdSlotCount = 1,
            repeatedLoaderCorrelationCount = 1,
        ),
        staticBlocked: Boolean = false,
        redirected: Boolean = false,
        functionalConflict: Boolean = false,
    ): AdaptiveAdObservation = AdaptiveAdObservation(
        profileId = profile.id,
        host = host,
        path = path,
        pageType = PageType.UNKNOWN,
        observedAtMs = atMs,
        evidence = evidence,
        thirdParty = host != "site-a.example",
        pathScoped = true,
        functionalConflict = functionalConflict,
        blockedByStaticRule = staticBlocked,
        correlatedWithRedirect = redirected,
        siteScope = "site-a.example",
    )

    private fun rawRequest(atMs: Long): AdaptiveRequestObservation = requireNotNull(
        AdaptiveObservationFactory.request(
            profile, siteA, "https://raw-third-party.example/runtime.js",
            blockedByStaticRule = false, correlatedWithRedirect = false, functionalEvidence = false,
            resourceKind = AdaptiveResourceKind.OTHER, observedAtMs = atMs,
        ),
    )

    private fun decide(engine: AdaptiveShieldEngine, url: String): AdaptiveDecision =
        engine.decideRequest(
            scopeA, policy, url, AdaptiveResourceKind.OTHER,
            false, true, AdaptiveShieldMode.AUTO_SAFE, 10,
        )
}
