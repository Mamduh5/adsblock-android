package com.example.siteshield

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePersistenceLimitTest {
    private val profile = MangakakalotProfile.profile

    @Test
    fun `sanitized learned records survive codec restart without private URL data`() {
        val engine = AdaptiveShieldEngine()
        repeat(3) { index ->
            engine.observe(
                AdaptiveNavigationObservation(
                    profile.id,
                    "ad-host.example",
                    PageType.CHAPTER_READER,
                    index + 1L,
                    popup = true,
                    blockedBySourcePolicy = true,
                ),
                profile.adaptivePolicy,
                AdaptiveShieldMode.AUTO_SAFE,
            )
        }
        val encoded = AdaptiveStateCodec.encode(engine.snapshot(4))
        val restarted = AdaptiveShieldEngine(AdaptiveStateCodec.decode(encoded))

        assertFalse(encoded.contains("token="))
        assertFalse(encoded.contains("SECRET"))
        assertEquals(AdaptiveCandidateState.LEARNED, restarted.snapshot(4).single().state)
    }

    @Test
    fun `corrupt and future persistence formats are rejected`() {
        assertTrue(AdaptiveStateCodec.decode("v2\nunknown").isEmpty())
        assertTrue(AdaptiveStateCodec.decode("v1\nbroken").isEmpty())
    }

    @Test
    fun `candidate count is bounded per profile`() {
        val config = AdaptiveLearningConfig(maxCandidatesPerProfile = 4, maxLearnedRulesPerProfile = 2)
        val engine = AdaptiveShieldEngine(config = config)
        repeat(10) { index ->
            engine.observe(
                AdaptiveNavigationObservation(
                    profile.id,
                    "host-$index.example",
                    PageType.CHAPTER_READER,
                    index + 1L,
                    popup = true,
                    blockedBySourcePolicy = true,
                ),
                profile.adaptivePolicy,
                AdaptiveShieldMode.LEARN,
            )
        }

        assertTrue(engine.snapshot(20).size <= 4)
    }

    @Test
    fun `learned rule count is bounded and rules can be disabled or forgotten`() {
        val config = AdaptiveLearningConfig(maxCandidatesPerProfile = 8, maxLearnedRulesPerProfile = 2)
        val engine = AdaptiveShieldEngine(config = config)
        repeat(3) { hostIndex ->
            repeat(3) { occurrence ->
                engine.observe(
                    AdaptiveNavigationObservation(
                        profile.id,
                        "learned-$hostIndex.example",
                        PageType.CHAPTER_READER,
                        (hostIndex * 10 + occurrence).toLong(),
                        popup = true,
                        blockedBySourcePolicy = true,
                    ),
                    profile.adaptivePolicy,
                    AdaptiveShieldMode.AUTO_SAFE,
                )
            }
        }
        assertEquals(2, engine.snapshot(40).count { it.state == AdaptiveCandidateState.LEARNED })

        val learned = engine.snapshot(40).first { it.state == AdaptiveCandidateState.LEARNED }
        assertEquals(AdaptiveCandidateState.REJECTED, engine.disable(profile.id, learned.id, 41)?.state)
        engine.forget(profile.id, learned.id)
        assertTrue(engine.snapshot(42).none { it.id == learned.id })
    }

    @Test
    fun `concurrent observations produce deterministic counts`() {
        val engine = AdaptiveShieldEngine()
        val workers = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(40)
        repeat(40) { index ->
            workers.execute {
                engine.observe(
                    AdaptiveNavigationObservation(
                        profile.id,
                        "concurrent-ad.example",
                        PageType.CHAPTER_READER,
                        index + 1L,
                        popup = true,
                        blockedBySourcePolicy = true,
                    ),
                    profile.adaptivePolicy,
                    AdaptiveShieldMode.LEARN,
                )
                latch.countDown()
            }
        }
        latch.await()
        workers.shutdown()

        assertEquals(40, engine.snapshot(50).single().occurrenceCount)
    }
}
