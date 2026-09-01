package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePolicyTest {
    @Test
    fun `mode defaults to local learn and invalid values fall back safely`() {
        assertEquals(AdaptiveShieldMode.LEARN, AdaptiveShieldMode.initialMode())
        assertEquals(AdaptiveShieldMode.LEARN, AdaptiveShieldMode.fromStoredValue("unknown"))
        assertEquals(AdaptiveShieldMode.AUTO_SAFE, AdaptiveShieldMode.fromStoredValue("AUTO_SAFE"))
    }

    @Test
    fun `mangakakalot enables bounded safe categories without dom promotion`() {
        val policy = MangakakalotProfile.profile.adaptivePolicy
        assertTrue(policy.enabled)
        assertTrue(policy.observeOffsiteNavigations)
        assertTrue(AdaptiveCandidateType.OFFSITE_REDIRECT_HOST in policy.autoPromoteTypes)
        assertTrue(AdaptiveCandidateType.FIRST_PARTY_LOADER in policy.autoPromoteTypes)
        assertFalse(AdaptiveCandidateType.DOM_STRUCTURE in policy.autoPromoteTypes)
    }

    @Test
    fun `aquareader permits redirect learning while dynamic profiles remain observation only`() {
        assertEquals(
            setOf(AdaptiveCandidateType.OFFSITE_REDIRECT_HOST),
            AquaReaderProfile.profile.adaptivePolicy.autoPromoteTypes,
        )
        listOf(
            YouTubeProfile.profile,
            FacebookProfile.profile,
            PalworldGgProfile.profile,
        ).forEach { profile ->
            assertTrue(profile.adaptivePolicy.enabled)
            assertTrue(profile.adaptivePolicy.autoPromoteTypes.isEmpty())
        }
        assertTrue(GenericWebProfile.profile.adaptivePolicy.enabled)
        assertTrue(GenericWebProfile.profile.adaptivePolicy.observeThirdPartyRequests)
    }

    @Test
    fun `media classification supports direct enforcement protection`() {
        assertEquals(
            AdaptiveResourceKind.IMAGE,
            adaptiveResourceKind(
                "https://cdn.example/content/no-extension",
                mapOf("Accept" to "image/avif,image/webp"),
            ),
        )
        assertEquals(
            AdaptiveResourceKind.SCRIPT,
            adaptiveResourceKind("https://cdn.example/runtime.js", emptyMap()),
        )
    }
}
