package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericBlockerEngineTest {
    private val engine = GenericBlockerEngine()
    private val profile = MangakakalotProfile.profile

    @Test
    fun `allowed host classification accepts configured domain suffix`() {
        assertTrue(engine.isAllowedHost(profile, "www.mangakakalot.gg"))
        assertTrue(engine.isAllowedHost(profile, "cdn.mangakakalot.gg"))
    }

    @Test
    fun `allowed host classification rejects unrelated host`() {
        assertFalse(engine.isAllowedHost(profile, "example.com"))
    }

    @Test
    fun `blocked host classification catches exact blocked suffix`() {
        assertTrue(engine.isBlockedHost(profile, "track.doubleclick.net"))
    }

    @Test
    fun `blocked host classification catches suspicious host token`() {
        assertTrue(engine.isBlockedHost(profile, "cdn-popup-network.test"))
    }

    @Test
    fun `suspicious url detection catches redirect path`() {
        assertTrue(engine.isSuspiciousNavigation(profile, "https://www.mangakakalot.gg/redirect/out"))
    }

    @Test
    fun `suspicious url detection catches non web scheme`() {
        assertTrue(engine.isSuspiciousNavigation(profile, "intent://malicious"))
    }

    @Test
    fun `resource blocking catches ad resource token`() {
        assertTrue(engine.isBlockedResource(profile, "https://cdn.mangakakalot.gg/ads/banner.js"))
    }

    @Test
    fun `normal same site chapter resource is not blocked`() {
        assertFalse(engine.isBlockedResource(profile, "https://www.mangakakalot.gg/chapter/example/chapter-1"))
    }

    @Test
    fun `page type classification uses conservative url patterns`() {
        assertEquals(PageType.HOME_LIST_SEARCH, engine.classifyPageType(profile, "https://www.mangakakalot.gg/"))
        assertEquals(PageType.HOME_LIST_SEARCH, engine.classifyPageType(profile, "https://www.mangakakalot.gg/search/story/test"))
        assertEquals(PageType.DETAIL, engine.classifyPageType(profile, "https://www.mangakakalot.gg/manga/example"))
        assertEquals(PageType.CHAPTER_READER, engine.classifyPageType(profile, "https://www.mangakakalot.gg/chapter/example/chapter-1"))
        assertEquals(PageType.UNKNOWN, engine.classifyPageType(profile, "https://www.mangakakalot.gg/random/path"))
    }

    @Test
    fun `exact host and path request rules block confirmed chains`() {
        assertTrue(engine.isBlockedResource(profile, "https://oundhertobeconsist.org/floater?x=1"))
        assertTrue(engine.isBlockedResource(profile, "https://chubbyexemplaryhardiness.com/get/2090108"))
        assertTrue(engine.isBlockedResource(profile, "https://chubbyexemplaryhardiness.com/on.js"))
        assertTrue(engine.isBlockedResource(profile, "https://withagecomeswisdom.live/api/ads/get-info/v2"))
        assertFalse(engine.isBlockedResource(profile, "https://chubbyexemplaryhardiness.com/get/other"))
    }

    @Test
    fun `combined host query and first party request rules are conjunctive`() {
        assertTrue(engine.isBlockedResource(profile, "https://d2dxy39sqorbhv.cloudfront.net/pixel?syxdd=1257018"))
        assertFalse(engine.isBlockedResource(profile, "https://d2dxy39sqorbhv.cloudfront.net/pixel?syxdd=999"))
        assertTrue(engine.isBlockedResource(profile, "https://unknown.example/pixel?wbbcd=1246039"))
        assertTrue(engine.isBlockedResource(profile, "https://www.mangakakalot.gg/js/ads/admaven.js"))

        val firstPartyLoader = profile.baselinePolicy.requestRules.first { it.id == "first-party-admaven-loader" }
        assertTrue(firstPartyLoader.matches("https://www.mangakakalot.gg/js/ads/admaven.js", profile, isMainFrame = false))
        assertFalse(firstPartyLoader.matches("https://cdn.example.org/js/ads/admaven.js", profile, isMainFrame = false))
    }

    @Test
    fun `chapter reader policy blocks offsite main frame navigation without changing detail policy`() {
        val chapterUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1"
        val detailUrl = "https://www.mangakakalot.gg/manga/example"

        assertTrue(
            engine.isSuspiciousNavigation(
                profile,
                "https://example.org/open",
                currentPageUrl = chapterUrl,
                isMainFrame = true,
            ),
        )
        assertFalse(
            engine.isSuspiciousNavigation(
                profile,
                "https://example.org/open",
                currentPageUrl = detailUrl,
                isMainFrame = true,
            ),
        )
        assertFalse(engine.policyForUrl(profile, chapterUrl).promptForOffsiteMainFrameNavigations)
        assertTrue(engine.policyForUrl(profile, detailUrl).promptForOffsiteMainFrameNavigations)
    }

    @Test
    fun `rule selection keeps preferred profile for unknown resource urls`() {
        val selected = engine.profileForUrl("https://unknown-cdn.example/assets/app.js", profile)

        assertEquals("mangakakalot", selected.id)
    }

    @Test
    fun `suspicious cookie and storage keys use profile patterns`() {
        assertTrue(engine.isSuspiciousCookieKey(profile, "popup_seen"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "redirect_campaign"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "__PPU_abc"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "__BI_SESSION_abc"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "toct1257018"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "PBFP250225"))
        assertTrue(engine.isSuspiciousStorageKey(profile, "UGVyc2lzdFN0b3JhZ2U"))
        assertFalse(engine.isSuspiciousCookieKey(profile, "session_id"))
        assertFalse(engine.isSuspiciousCookieKey(profile, "__Secure-SID"))
        assertFalse(engine.isSuspiciousStorageKey(profile, "youtube_session"))
    }

    @Test
    fun `chapter dom cleanup preserves reader controls and does not blanket remove reader container`() {
        val domRules = engine.domRulesForUrl(
            profile,
            "https://www.mangakakalot.gg/chapter/example/chapter-1",
        )

        assertTrue(domRules.preserveSelectors.contains(".container-chapter-reader"))
        assertTrue(domRules.preserveSelectors.contains(".container-chapter-reader img"))
        assertTrue(domRules.preserveSelectors.contains(".navi-change-chapter"))
        assertTrue(domRules.preserveSelectors.contains("#server-image"))
        assertFalse(domRules.suspiciousSelectors.contains(".container-chapter-reader"))
    }
}
