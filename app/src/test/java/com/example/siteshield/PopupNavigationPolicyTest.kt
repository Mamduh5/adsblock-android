package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupNavigationPolicyTest {
    private val engine = GenericBlockerEngine()
    private val sourceUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1"

    @Test
    fun `mangakakalot popup keeps source policy for unknown and registered destinations`() {
        listOf(
            "https://outside.example/landing",
            "https://youtube.com/watch?v=video",
            "https://facebook.com/example-page",
        ).forEach { targetUrl ->
            assertEquals(
                targetUrl,
                BlockDecision.Block(BlockReason.OFFSITE_MAIN_FRAME),
                engine.popupNavigationDecision(
                    sourceProfile = MangakakalotProfile.profile,
                    targetUrl = targetUrl,
                    sourcePageUrl = sourceUrl,
                    hasUserGesture = true,
                    blockerEnabled = true,
                ),
            )
        }
    }

    @Test
    fun `mangakakalot popup allows same site navigation`() {
        assertEquals(
            BlockDecision.Allow,
            engine.popupNavigationDecision(
                sourceProfile = MangakakalotProfile.profile,
                targetUrl = "https://www.mangakakalot.gg/chapter/example/chapter-2",
                sourcePageUrl = sourceUrl,
                hasUserGesture = true,
                blockerEnabled = true,
            ),
        )
    }

    @Test
    fun `popup without gesture is blocked before destination profile resolution`() {
        assertEquals(
            BlockDecision.Block(BlockReason.POPUP_WITHOUT_USER_GESTURE),
            engine.popupNavigationDecision(
                sourceProfile = MangakakalotProfile.profile,
                targetUrl = "https://outside.example/landing",
                sourcePageUrl = sourceUrl,
                hasUserGesture = false,
                blockerEnabled = true,
            ),
        )
    }

    @Test
    fun `generic web user gesture popup remains allowed`() {
        assertEquals(
            BlockDecision.Allow,
            engine.popupNavigationDecision(
                sourceProfile = GenericWebProfile.profile,
                targetUrl = "https://outside.example/article",
                sourcePageUrl = "https://search.example/results",
                hasUserGesture = true,
                blockerEnabled = true,
            ),
        )
    }

    @Test
    fun `blocker off allows user gesture popup without weakening no gesture protection`() {
        assertEquals(
            BlockDecision.Allow,
            engine.popupNavigationDecision(
                sourceProfile = MangakakalotProfile.profile,
                targetUrl = "https://outside.example/landing",
                sourcePageUrl = sourceUrl,
                hasUserGesture = true,
                blockerEnabled = false,
            ),
        )
        assertEquals(
            BlockDecision.Block(BlockReason.POPUP_WITHOUT_USER_GESTURE),
            engine.popupNavigationDecision(
                sourceProfile = MangakakalotProfile.profile,
                targetUrl = "https://outside.example/landing",
                sourcePageUrl = sourceUrl,
                hasUserGesture = false,
                blockerEnabled = false,
            ),
        )
    }
}
