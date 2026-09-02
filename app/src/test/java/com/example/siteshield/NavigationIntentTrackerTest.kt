package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationIntentTrackerTest {
    private var now = 1_000L
    private fun tracker() = NavigationIntentTracker({ now }, validityMs = 500)

    @Test fun `same-site and external links match by normalized host and path`() {
        val tracker = tracker()
        val generation = tracker.documentStarted()
        assertTrue(tracker.record(generation, tracker.channelToken(), "reader.example", "/chapter/12", false))
        assertTrue(tracker.resolve("https://reader.example/chapter/12?reader=true", true, false).trusted)
        assertTrue(tracker.record(generation, tracker.channelToken(), "external.example", "/story", false))
        assertEquals(NavigationIntentCategory.PAGE_LINK_INTENDED,
            tracker.resolve("https://external.example/story", true, false).category)
    }

    @Test fun `target blank requires matching intended popup`() {
        val tracker = tracker()
        val generation = tracker.documentStarted()
        tracker.record(generation, tracker.channelToken(), "docs.example", "/help", true)
        assertTrue(tracker.resolve("https://docs.example/help", true, true).trusted)
        tracker.record(generation, tracker.channelToken(), "docs.example", "/help", false)
        assertFalse(tracker.resolve("https://docs.example/help", true, true).trusted)
    }

    @Test fun `generic tap and hijacked same-site click do not authorize unrelated host`() {
        val tracker = tracker()
        val generation = tracker.documentStarted()
        assertEquals(NavigationIntentCategory.CLICK_HIJACK_SUSPECTED,
            tracker.resolve("https://ad.example/pop", true, false).category)
        tracker.record(generation, tracker.channelToken(), "reader.example", "/chapter/2", false)
        assertEquals(NavigationIntentCategory.CLICK_HIJACK_SUSPECTED,
            tracker.resolve("https://ad.example/pop", true, false).category)
    }

    @Test fun `stale cross-generation and cross-tab intent cannot authorize`() {
        val first = tracker()
        val second = tracker()
        val generation = first.documentStarted()
        first.record(generation, first.channelToken(), "target.example", "/go", false)
        now += 501
        assertFalse(first.resolve("https://target.example/go", true, false).trusted)
        val oldGeneration = first.documentStarted()
        first.documentStarted()
        assertFalse(first.record(oldGeneration, first.channelToken(), "target.example", "/go", false))
        assertFalse(second.resolve("https://target.example/go", true, false).trusted)
    }

    @Test fun `app navigation and history targets remain trusted`() {
        val tracker = tracker()
        tracker.prepareAppOwned("https://account.example/login?next=home")
        assertEquals(NavigationIntentCategory.APP_EXPLICIT,
            tracker.resolve("https://account.example/login?next=home", false, false).category)
    }

    @Test fun `message parser accepts only compact destination facts`() {
        val token = "a".repeat(32)
        val parsed = NavigationIntentMessage.parse("[SiteShieldIntent] N1|3|$token|Example.COM|%2Fchapter%2F12|1")!!
        assertEquals("example.com", parsed.host)
        assertEquals("/chapter/{numeric}", parsed.path)
        assertTrue(parsed.targetBlank)
        assertEquals(null, NavigationIntentMessage.parse("[SiteShieldIntent] N1|3|$token|bad host|/|0"))
    }

    @Test fun `intent mismatch and popup are independent adaptive evidence`() {
        val profile = GenericWebProfile.profile
        val engine = AdaptiveShieldEngine()
        repeat(3) { index ->
            val observation = AdaptiveObservationFactory.navigation(
                profile = profile,
                sourceUrl = "https://reader.example/chapter/1",
                targetUrl = "https://unrelated-ad.example/pop",
                popup = true,
                blockedBySourcePolicy = false,
                intentMismatch = true,
                observedAtMs = index.toLong(),
            )!!
            engine.observe(observation, profile.adaptivePolicy, AdaptiveShieldMode.LEARN)
        }
        val record = engine.snapshot(4).single()
        assertEquals(3, record.intentMismatchCount)
        assertEquals(3, record.popupCount)
    }
}
