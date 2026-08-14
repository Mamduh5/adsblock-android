package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericWebProfileTest {
    private val profile = GenericWebProfile.profile

    @Test
    fun `generic profile owns arbitrary hosts without aggressive cleanup`() {
        assertEquals("generic-web", profile.id)
        assertEquals("Browse", profile.displayName)
        assertTrue(profile.allowedHosts.isEmpty())
        assertFalse(profile.baselinePolicy.promptForOffsiteMainFrameNavigations)
        assertFalse(profile.baselinePolicy.domRules.enableGenericOverlayHeuristics)
    }

    @Test
    fun `generic data saver is compatible in balanced and blocks images in max`() {
        assertFalse(profile.dataSaverPolicy.blockNetworkImages(DataSaverMode.OFF, PageType.UNKNOWN))
        assertFalse(profile.dataSaverPolicy.blockNetworkImages(DataSaverMode.BALANCED, PageType.UNKNOWN))
        assertTrue(profile.dataSaverPolicy.blockNetworkImages(DataSaverMode.MAX, PageType.UNKNOWN))
    }

    @Test
    fun `generic allows ordinary cross-site https and blocks unsafe schemes`() {
        val engine = GenericBlockerEngine()
        assertEquals(
            BlockDecision.Allow,
            engine.navigationDecision(profile, "https://site-b.example/", "https://site-a.example/"),
        )
        assertEquals(
            BlockDecision.Block(BlockReason.UNSUPPORTED_SCHEME),
            engine.navigationDecision(profile, "javascript:alert(1)", "https://site-a.example/"),
        )
    }
}
