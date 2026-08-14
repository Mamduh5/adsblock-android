package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSaverPolicyTest {
    private val profiles = listOf(
        GenericWebProfile.profile,
        MangakakalotProfile.profile,
        PalworldGgProfile.profile,
        AquaReaderProfile.profile,
        YouTubeProfile.profile,
        FacebookProfile.profile,
    )

    @Test
    fun `all production profiles block only explicit prefetch in balanced`() {
        profiles.forEach { profile ->
            assertEquals(
                profile.id,
                DataSaverDecision.Block("explicit-prefetch"),
                DataSaverEngine.decide(
                    DataSaverMode.BALANCED,
                    profile.dataSaverPolicy,
                    request(headers = mapOf("Sec-Purpose" to "prefetch")),
                ),
            )
            assertEquals(
                profile.id,
                DataSaverDecision.Allow,
                DataSaverEngine.decide(
                    DataSaverMode.BALANCED,
                    profile.dataSaverPolicy,
                    request(headers = mapOf("Accept" to "image/webp")),
                ),
            )
        }
    }

    @Test
    fun `off never adds a saver block and main-frame navigation remains allowed`() {
        val policy = FacebookProfile.profile.dataSaverPolicy
        assertEquals(
            DataSaverDecision.Allow,
            DataSaverEngine.decide(
                DataSaverMode.OFF,
                policy,
                request(headers = mapOf("Purpose" to "prefetch")),
            ),
        )
        assertEquals(
            DataSaverDecision.Allow,
            DataSaverEngine.decide(
                DataSaverMode.MAX,
                policy,
                request(isForMainFrame = true, headers = mapOf("Purpose" to "prefetch")),
            ),
        )
    }

    @Test
    fun `nearby preload post and gesture requests are not guessed as prefetch`() {
        val policy = YouTubeProfile.profile.dataSaverPolicy
        listOf(
            request(headers = mapOf("Purpose" to "preload")),
            request(method = "POST", headers = mapOf("Purpose" to "prefetch")),
            request(hasGesture = true),
        ).forEach { request ->
            assertEquals(DataSaverDecision.Allow, DataSaverEngine.decide(DataSaverMode.BALANCED, policy, request))
        }
    }

    @Test
    fun `max image policies protect primary content routes`() {
        assertTrue(MangakakalotProfile.profile.dataSaverPolicy.blockNetworkImages(DataSaverMode.MAX, PageType.DETAIL))
        assertFalse(
            MangakakalotProfile.profile.dataSaverPolicy.blockNetworkImages(
                DataSaverMode.MAX,
                PageType.CHAPTER_READER,
            ),
        )
        assertFalse(
            AquaReaderProfile.profile.dataSaverPolicy.blockNetworkImages(
                DataSaverMode.MAX,
                PageType.CHAPTER_READER,
            ),
        )
        assertFalse(
            PalworldGgProfile.profile.dataSaverPolicy.blockNetworkImages(
                DataSaverMode.MAX,
                PageType.INTERACTIVE_MAP,
            ),
        )
        assertFalse(
            PalworldGgProfile.profile.dataSaverPolicy.blockNetworkImages(
                DataSaverMode.MAX,
                PageType.INTERACTIVE_TOOL,
            ),
        )
        assertFalse(
            YouTubeProfile.profile.dataSaverPolicy.blockNetworkImages(
                DataSaverMode.MAX,
                PageType.VIDEO_WATCH,
            ),
        )
        assertTrue(FacebookProfile.profile.dataSaverPolicy.blockNetworkImages(DataSaverMode.MAX, PageType.HOME_LIST_SEARCH))
    }

    @Test
    fun `leaving max always restores normal network image policy`() {
        profiles.forEach { profile ->
            val policy = profile.dataSaverPolicy
            assertFalse(profile.id, policy.blockNetworkImages(DataSaverMode.OFF, PageType.HOME_LIST_SEARCH))
            assertFalse(profile.id, policy.blockNetworkImages(DataSaverMode.BALANCED, PageType.HOME_LIST_SEARCH))
        }
    }

    private fun request(
        method: String = "GET",
        isForMainFrame: Boolean = false,
        hasGesture: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ) = DataSaverRequestContext(method, isForMainFrame, hasGesture, headers)
}
