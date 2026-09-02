package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveShieldV31Test {
    private val profile = GenericWebProfile.profile
    private val siteA = "https://site-a.example/article"
    private val scopeA = AdaptiveScope(profile.id, "site-a.example")

    @Test
    fun `A4 compact reports parse slot identity and metadata safely`() {
        val drain = parseAdaptiveDomAdDrain(
            "M4\t1\t3\t7\nA4\t5\tIFRAME\t17\tads.example\t/render/{numeric}\ttrue\ttrue",
        )

        assertTrue(drain.observerInstalled)
        assertEquals(3, drain.overflowCount)
        assertEquals(7, drain.pendingCount)
        assertEquals(17, drain.reports.single().slotId)
    }

    @Test
    fun `invalid A4 slot ids and malformed reports fail safely`() {
        assertTrue(parseAdaptiveDomAdReports("A4\t5\tIFRAME\t0\tads.example\t/render\ttrue\ttrue").isEmpty())
        assertTrue(parseAdaptiveDomAdReports("A4\t5\tIFRAME\t4097\tads.example\t/render\ttrue\ttrue").isEmpty())
        assertTrue(parseAdaptiveDomAdReports("A4\t5\tIFRAME\t1\tads.example").isEmpty())
        assertTrue(parseAdaptiveDomAdReports("A3\t5\tIFRAME\t1\tads.example\t/render\ttrue\ttrue").isEmpty())
    }

    @Test
    fun `same slot structure iframe and loader retain one correlation identity`() {
        val reports = parseAdaptiveDomAdReports(
            listOf(
                "A4\t1\tSTRUCTURE\t7\tsite-a.example\t/\tfalse\ttrue",
                "A4\t5\tIFRAME\t7\tframes.example\t/render\ttrue\ttrue",
                "A4\t1\tLOADER\t7\tads.example\t/loader.js\ttrue\ttrue",
            ).joinToString("\n"),
        )

        assertEquals(setOf(7), reports.map(AdaptiveDomAdReport::slotId).toSet())
        assertEquals(setOf(AdaptiveAdResourceRole.STRUCTURE, AdaptiveAdResourceRole.IFRAME, AdaptiveAdResourceRole.LOADER),
            reports.map(AdaptiveDomAdReport::role).toSet())
    }

    @Test
    fun `loader from slot A does not suppress safe inference for slot B`() {
        withController(AdaptiveShieldMode.LEARN) { controller, _ ->
            controller.observeRequest(profile, siteA, "https://direct.example/direct-a.js", false, AdaptiveResourceKind.SCRIPT)
            controller.observeRequest(profile, siteA, "https://inferred.example/inferred-b.js", false, AdaptiveResourceKind.SCRIPT)
            controller.observeRequest(profile, siteA, "https://frames.example/render/1", false, AdaptiveResourceKind.OTHER)
            controller.observeDomAdEvidence(
                profile,
                siteA,
                listOf(
                    report(1, AdaptiveAdResourceRole.STRUCTURE, "site-a.example", "/", explicit = true),
                    report(1, AdaptiveAdResourceRole.IFRAME, "frames.example", "/render/{numeric}", explicit = true),
                    report(1, AdaptiveAdResourceRole.LOADER, "direct.example", "/direct-a.js", explicit = true),
                    report(2, AdaptiveAdResourceRole.STRUCTURE, "site-a.example", "/", explicit = true),
                    report(2, AdaptiveAdResourceRole.IFRAME, "frames.example", "/render/{numeric}", explicit = true),
                ),
            )

            val adHosts = controller.records(scopeA)
                .filter { it.type == AdaptiveCandidateType.AD_RESOURCE }
                .map(AdaptiveRecord::host)
                .toSet()
            assertTrue("direct.example" in adHosts)
            assertTrue("inferred.example" in adHosts)
        }
    }

    @Test
    fun `two slot ids preserve two observations for the same normalized iframe path`() {
        withController(AdaptiveShieldMode.LEARN) { controller, _ ->
            controller.observeRequest(profile, siteA, "https://frames.example/render/123", false, AdaptiveResourceKind.OTHER)
            controller.observeDomAdEvidence(
                profile,
                siteA,
                listOf(
                    report(4, AdaptiveAdResourceRole.STRUCTURE, "site-a.example", "/", explicit = true),
                    report(4, AdaptiveAdResourceRole.IFRAME, "frames.example", "/render/{numeric}", explicit = true),
                    report(9, AdaptiveAdResourceRole.STRUCTURE, "site-a.example", "/", explicit = true),
                    report(9, AdaptiveAdResourceRole.IFRAME, "frames.example", "/render/{numeric}", explicit = true),
                ),
            )

            val frame = controller.records(scopeA).single {
                it.type == AdaptiveCandidateType.AD_RESOURCE && it.host == "frames.example"
            }
            assertEquals(2, frame.occurrenceCount)
            assertEquals(2, frame.adEvidence.adIframeCorrelationCount)
        }
    }

    @Test
    fun `compact native drains remain bounded to thirty two reports`() {
        val serialized = (1..64).joinToString("\n") { slot ->
            "A4\t1\tSTRUCTURE\t$slot\tsite-a.example\t/\tfalse\ttrue"
        }
        assertEquals(32, parseAdaptiveDomAdReports(serialized).size)
    }

    @Test
    fun `sponsored child attribution requires exact token and structural owner`() {
        val sponsored = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                shortAttribution = "Sponsored",
                structuralAdContext = true,
                iframeUrl = "https://ads.example/render",
                slotId = 8,
            ),
        )
        val prose = AdaptiveDomAdClassifier.classify(
            AdaptiveDomNodeFacts(
                shortAttribution = "Sponsored content policy",
                structuralAdContext = true,
                iframeUrl = "https://publisher.example/policy",
            ),
        )

        assertEquals(8, sponsored.single().slotId)
        assertTrue(prose.isEmpty())
    }

    @Test
    fun `sticky layout without ad identity remains insufficient`() {
        assertTrue(
            AdaptiveDomAdClassifier.classify(
                AdaptiveDomNodeFacts(
                    fixedOrSticky = true,
                    highZIndex = true,
                    viewportCoverage = 0.8,
                    structuralAdContext = true,
                    iframeUrl = "https://widgets.example/dialog",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `script resource classification uses destination mime and extensions conservatively`() {
        assertEquals(AdaptiveResourceKind.SCRIPT, adaptiveResourceKind("https://cdn.example/app.js", emptyMap()))
        assertEquals(AdaptiveResourceKind.SCRIPT, adaptiveResourceKind("https://cdn.example/app.mjs", emptyMap()))
        assertEquals(AdaptiveResourceKind.SCRIPT,
            adaptiveResourceKind("https://cdn.example/runtime", mapOf("Accept" to "text/javascript")))
        assertEquals(AdaptiveResourceKind.SCRIPT,
            adaptiveResourceKind("https://cdn.example/runtime", mapOf("Sec-Fetch-Dest" to "script")))
        assertEquals(AdaptiveResourceKind.OTHER,
            adaptiveResourceKind("https://cdn.example/runtime", mapOf("Accept" to "application/json")))
    }

    @Test
    fun `blocker off still allows learn observation but prevents enforcement and cleanup`() {
        withController(AdaptiveShieldMode.LEARN) { controller, _ ->
            controller.observeRequest(profile, siteA, "https://ads.example/loader.js", false, AdaptiveResourceKind.SCRIPT)
            assertTrue(controller.records(scopeA).isNotEmpty())
            assertEquals(
                AdaptiveDecision.Allow,
                controller.decideRequest(
                    profile, siteA, "https://ads.example/loader.js", AdaptiveResourceKind.SCRIPT,
                    blockerEnabled = false,
                ),
            )
        }
        assertFalse(AdaptiveRuntimeModePolicy.performsStaticCleanup(false))
        assertTrue(AdaptiveRuntimeModePolicy.observes(AdaptiveShieldMode.LEARN))
        assertFalse(AdaptiveRuntimeModePolicy.enforces(AdaptiveShieldMode.LEARN, true))
    }

    @Test
    fun `adaptive off records no request evidence`() {
        withController(AdaptiveShieldMode.OFF) { controller, _ ->
            controller.observeRequest(profile, siteA, "https://ads.example/loader.js", false, AdaptiveResourceKind.SCRIPT)
            assertTrue(controller.records(scopeA).isEmpty())
        }
        assertFalse(AdaptiveRuntimeModePolicy.observes(AdaptiveShieldMode.OFF))
    }

    @Test
    fun `lifecycle avoids duplicate loops and invalidates old document callbacks`() {
        val lifecycle = AdaptiveObserverLifecycle()
        lifecycle.attach("tab-a")
        val oldGeneration = lifecycle.documentStarted("tab-a")
        assertTrue(lifecycle.schedulePeriodic("tab-a", oldGeneration))
        assertFalse(lifecycle.schedulePeriodic("tab-a", oldGeneration))
        assertTrue(lifecycle.scheduleResourceDrain("tab-a", oldGeneration))
        assertFalse(lifecycle.scheduleResourceDrain("tab-a", oldGeneration))

        val newGeneration = lifecycle.documentStarted("tab-a")
        assertFalse(lifecycle.consumePeriodic("tab-a", oldGeneration))
        assertFalse(lifecycle.consumeResourceDrain("tab-a", oldGeneration))
        assertTrue(lifecycle.isCurrentActive("tab-a", newGeneration))
    }

    @Test
    fun `detached and destroyed tabs cannot retain polling ownership`() {
        val lifecycle = AdaptiveObserverLifecycle()
        val generation = lifecycle.attach("tab-a")
        assertTrue(lifecycle.schedulePeriodic("tab-a", generation))
        lifecycle.detach("tab-a")
        assertFalse(lifecycle.consumePeriodic("tab-a", generation))
        assertFalse(lifecycle.scheduleResourceDrain("tab-a", generation))
        lifecycle.destroy("tab-a")
        assertFalse(lifecycle.isCurrentActive("tab-a", generation))
    }

    @Test
    fun `preference migration reads legacy key then writes v4 before removing legacy`() {
        val expected = persistedRecord()
        val store = FakePreferenceStore(
            mutableMapOf(
                SharedPreferencesAdaptiveStatePersistence.KEY_STATE_LEGACY to
                    AdaptiveStateCodec.encode(listOf(expected)),
            ),
        )
        val persistence = SharedPreferencesAdaptiveStatePersistence(store, Unit)

        assertEquals(listOf(expected), persistence.load())
        persistence.save(listOf(expected))
        assertNotNull(store.values[SharedPreferencesAdaptiveStatePersistence.KEY_STATE_V4])
        assertNull(store.values[SharedPreferencesAdaptiveStatePersistence.KEY_STATE_LEGACY])
    }

    @Test
    fun `failed v4 preference write preserves legacy state`() {
        val legacy = AdaptiveStateCodec.encode(listOf(persistedRecord()))
        val store = FakePreferenceStore(
            mutableMapOf(SharedPreferencesAdaptiveStatePersistence.KEY_STATE_LEGACY to legacy),
            failWrites = true,
        )
        SharedPreferencesAdaptiveStatePersistence(store, Unit).save(listOf(persistedRecord()))
        assertEquals(legacy, store.values[SharedPreferencesAdaptiveStatePersistence.KEY_STATE_LEGACY])
    }

    private fun report(
        slotId: Int,
        role: AdaptiveAdResourceRole,
        host: String,
        path: String,
        explicit: Boolean,
    ): AdaptiveDomAdReport = AdaptiveDomAdReport(
        role, host, path, explicit, false, role == AdaptiveAdResourceRole.IFRAME, false,
        pathScoped = role != AdaptiveAdResourceRole.STRUCTURE,
        slotId = slotId,
    )

    private fun persistedRecord(): AdaptiveRecord = AdaptiveRecord(
        id = "adaptive:generic-web:site=site-a.example:third_party_request_host:ads.example:host",
        profileId = profile.id,
        type = AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST,
        riskTier = AdaptiveRiskTier.MEDIUM_RISK,
        host = "ads.example",
        path = null,
        state = AdaptiveCandidateState.CANDIDATE,
        occurrenceCount = 3,
        popupCount = 0,
        sourcePolicyBlockCount = 0,
        staticBlockCount = 0,
        thirdPartyCount = 3,
        redirectCorrelationCount = 0,
        functionalEvidenceCount = 0,
        firstSeenAtMs = 1,
        lastSeenAtMs = 3,
        learnedAtMs = null,
        rejectedAtMs = null,
        score = 24,
        confidence = 12,
        siteScope = "site-a.example",
    )

    private fun withController(
        mode: AdaptiveShieldMode,
        block: (AdaptiveShieldController, MutableClock) -> Unit,
    ) {
        val clock = MutableClock(1)
        val controller = AdaptiveShieldController(
            persistence = object : AdaptiveStatePersistence {
                override fun load(): List<AdaptiveRecord> = emptyList()
                override fun save(records: List<AdaptiveRecord>) = Unit
            },
            initialMode = mode,
            profileById = { profile },
            onEvent = {},
            clock = AdaptiveClock { clock.nowMs },
            persistDelayMs = 1_000_000,
        )
        try {
            block(controller, clock)
        } finally {
            controller.close()
        }
    }

    private data class MutableClock(var nowMs: Long)

    private class FakePreferenceStore(
        val values: MutableMap<String, String>,
        private val failWrites: Boolean = false,
    ) : AdaptivePreferenceStore {
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String): Boolean {
            if (failWrites) return false
            values[key] = value
            return true
        }
        override fun remove(key: String): Boolean = values.remove(key) != null
    }
}
