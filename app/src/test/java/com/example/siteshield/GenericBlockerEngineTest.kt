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
    fun `matched request rule is reportable for debug logging`() {
        val matchedRule = engine.matchingRequestRule(
            profile,
            "https://oundhertobeconsist.org/floater",
            currentPageUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1",
            isMainFrame = false,
        )

        assertEquals("oundhertobeconsist-floater", matchedRule?.id)
    }

    @Test
    fun `mangakakalot blocks offsite main frame navigation on every page type`() {
        val chapterUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1"
        val detailUrl = "https://www.mangakakalot.gg/manga/example"
        val homeUrl = "https://www.mangakakalot.gg/"

        listOf(chapterUrl, detailUrl, homeUrl).forEach { currentUrl ->
            listOf("http://example.org/open", "https://example.org/open").forEach { targetUrl ->
                assertEquals(
                    BlockDecision.Block(BlockReason.OFFSITE_MAIN_FRAME),
                    engine.navigationDecision(
                        profile,
                        targetUrl,
                        currentPageUrl = currentUrl,
                        isMainFrame = true,
                    ),
                )
            }
        }
        assertFalse(engine.policyForUrl(profile, chapterUrl).promptForOffsiteMainFrameNavigations)
        assertFalse(engine.policyForUrl(profile, detailUrl).promptForOffsiteMainFrameNavigations)
    }

    @Test
    fun `mangakakalot allows same site chapter detail and subdomain navigation`() {
        val chapterUrl = "https://www.mangakakalot.gg/chapter/example/chapter-1"

        listOf(
            "https://www.mangakakalot.gg/chapter/example/chapter-2",
            "https://www.mangakakalot.gg/manga/example",
            "https://cdn.mangakakalot.gg/chapter/example/chapter-2",
        ).forEach { targetUrl ->
            assertEquals(
                BlockDecision.Allow,
                engine.navigationDecision(profile, targetUrl, chapterUrl, isMainFrame = true),
            )
        }
    }

    @Test
    fun `mangakakalot hostile destination remains an explicit block`() {
        val decision = engine.navigationDecision(
            profile,
            "https://oundhertobeconsist.org/floater",
            "https://www.mangakakalot.gg/",
            isMainFrame = true,
        )

        assertEquals(
            BlockDecision.Block(BlockReason.REQUEST_RULE, "oundhertobeconsist-floater"),
            decision,
        )
    }

    @Test
    fun `another profile can still prompt for offsite main frame navigation`() {
        val promptProfile = SiteProfile(
            id = "prompting-test-profile",
            displayName = "Prompting test profile",
            startUrl = "https://reader.example/",
            allowedHosts = listOf(HostPattern.DomainSuffix("reader.example")),
            baselinePolicy = PagePolicy(
                blockOffsiteMainFrameNavigations = false,
                promptForOffsiteMainFrameNavigations = true,
            ),
        )

        assertEquals(
            BlockDecision.PromptExternal("https://external.example/article"),
            engine.navigationDecision(
                promptProfile,
                "https://external.example/article",
                "https://reader.example/chapter/1",
                isMainFrame = true,
            ),
        )
    }

    @Test
    fun `page type policy summary reports strict mangakakalot policy`() {
        val summary = engine.describePolicy(profile, PageType.CHAPTER_READER)

        assertTrue(summary.contains("pageType=CHAPTER_READER"))
        assertTrue(summary.contains("offsiteMainFrameDenied=true"))
        assertTrue(summary.contains("requestRules="))
    }

    @Test
    fun `top level profile selection keeps current profile for unknown urls`() {
        val selected = engine.profileForTopLevelUrl("https://unknown-cdn.example/assets/app.js", profile)

        assertEquals("mangakakalot", selected.id)
    }

    @Test
    fun `resource decision reports matched rule without reevaluating in caller`() {
        val decision = engine.resourceDecision(
            profile,
            "https://oundhertobeconsist.org/floater",
            "https://www.mangakakalot.gg/chapter/example/chapter-1",
        )

        assertEquals(
            BlockDecision.Block(BlockReason.REQUEST_RULE, "oundhertobeconsist-floater"),
            decision,
        )
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
