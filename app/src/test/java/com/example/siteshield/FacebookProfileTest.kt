package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookProfileTest {
    private val engine = GenericBlockerEngine()
    private val profile = FacebookProfile.profile

    @Test
    fun `classifies representative facebook routes`() {
        mapOf(
            "https://www.facebook.com/" to PageType.HOME_LIST_SEARCH,
            "https://m.facebook.com/" to PageType.HOME_LIST_SEARCH,
            "https://th-th.facebook.com/search/top/?q=meta" to PageType.HOME_LIST_SEARCH,
            "https://www.facebook.com/marketplace/" to PageType.HOME_LIST_SEARCH,
            "https://www.facebook.com/marketplace/search/?query=bicycle" to PageType.HOME_LIST_SEARCH,
            "https://m.facebook.com/notifications/" to PageType.HOME_LIST_SEARCH,
            "https://m.facebook.com/menu/" to PageType.HOME_LIST_SEARCH,
            "https://www.facebook.com/profile.php?id=123" to PageType.DETAIL,
            "https://www.facebook.com/Meta" to PageType.DETAIL,
            "https://www.facebook.com/story.php?story_fbid=456&id=123" to PageType.DETAIL,
            "https://www.facebook.com/somepage/posts/456" to PageType.DETAIL,
            "https://www.facebook.com/photo.php?fbid=456" to PageType.DETAIL,
            "https://www.facebook.com/reel/123" to PageType.VIDEO_WATCH,
            "https://www.facebook.com/watch/" to PageType.VIDEO_WATCH,
            "https://www.facebook.com/somepage/videos/456" to PageType.VIDEO_WATCH,
            "https://www.facebook.com/help/center" to PageType.UNKNOWN,
            "https://www.facebook.com/gaming/play/example" to PageType.UNKNOWN,
        ).forEach { (url, expected) ->
            assertEquals(url, expected, engine.classifyPageType(profile, url))
        }
    }

    @Test
    fun `navigation allows facebook blocks known hostile hosts and prompts ordinary external links`() {
        val currentUrl = "https://www.facebook.com/"

        listOf(
            "https://m.facebook.com/marketplace/",
            "https://th-th.facebook.com/Meta",
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fcreator.example",
        ).forEach { url ->
            assertEquals(url, BlockDecision.Allow, engine.navigationDecision(profile, url, currentUrl))
        }
        assertEquals(
            BlockDecision.Block(BlockReason.BLOCKED_HOST),
            engine.navigationDecision(profile, "https://www.popads.net/click", currentUrl),
        )
        assertEquals(
            BlockDecision.PromptExternal("https://creator.example/store"),
            engine.navigationDecision(profile, "https://creator.example/store", currentUrl),
        )
    }

    @Test
    fun `allows facebook app api image video and support resources`() {
        val currentUrl = "https://www.facebook.com/marketplace/"
        listOf(
            "https://www.facebook.com/api/graphql/",
            "https://www.facebook.com/ajax/bz",
            "https://static.xx.fbcdn.net/rsrc.php/v4/yx/r/app.js",
            "https://scontent.fbkk1-1.fna.fbcdn.net/v/t39.30808-6/image.jpg",
            "https://video.fbkk1-1.fna.fbcdn.net/o1/v/t2/video.mp4",
            "https://edge-chat.facebook.com/pull",
            "https://upload.facebook.com/ajax/mercury/upload.php",
            "https://fonts.googleapis.com/css2?family=Roboto",
        ).forEach { url ->
            assertEquals(url, BlockDecision.Allow, engine.resourceDecision(profile, url, currentUrl))
        }

        assertTrue(profile.baselinePolicy.requestRules.isEmpty())
    }

    @Test
    fun `cleanup avoids unproven facebook ad and overlay heuristics`() {
        val rules = engine.domRulesForUrl(profile, "https://www.facebook.com/")

        assertEquals(
            listOf("[data-mcomponent='MContainer'][data-type='container'][data-comp-id='22222']"),
            rules.suspiciousSelectors,
        )
        assertTrue(rules.suspiciousClassTokens.isEmpty())
        assertTrue(rules.suspiciousUrlTokens.isEmpty())
        assertTrue(rules.baitTextTokens.isEmpty())
        assertTrue(rules.junkTextTokens.isEmpty())
        assertTrue(rules.preserveSelectors.contains("[role='feed']"))
        assertTrue(rules.preserveSelectors.contains("[role='article']"))
        assertTrue(rules.preserveSelectors.contains("[role='dialog']"))
        assertTrue(rules.preserveSelectors.contains("video"))
        assertFalse(rules.enableGenericOverlayHeuristics)
    }

    @Test
    fun `observed sponsored marker resolves only through bounded feed item configuration`() {
        val rule = engine.domRulesForUrl(profile, "https://www.facebook.com/")
            .ancestorCleanupRules
            .single { it.removalReason == "facebook-sponsored-feed" }

        assertTrue(rule.matchesMarkerText("ที่ได้รับการสนับสนุน\uDB81\uDF8B\uDB85\uDE77"))
        assertFalse(rule.matchesMarkerText("Suggested for you"))
        assertFalse(rule.matchesMarkerText("People you may know"))
        assertFalse(rule.matchesMarkerText("ordinary post containing Sponsored discussion"))
        assertEquals("[data-mcomponent='TextArea'][data-type='text']", rule.markerSelector)
        assertEquals("[data-mcomponent='MContainer'][data-type='container']", rule.ancestorSelector)
        assertEquals(
            "[data-mcomponent='MContainer'][data-type='vscroller']",
            rule.ancestorParentSelector,
        )
        assertEquals(4, rule.maxAncestorDepth)
        assertEquals("facebook-sponsored-feed", rule.removalReason)
    }

    @Test
    fun `observed reels open app marker resolves only to its button`() {
        val rule = engine.domRulesForUrl(profile, "https://www.facebook.com/reel/123")
            .ancestorCleanupRules
            .single { it.removalReason == "facebook-app-promo-reels" }

        assertTrue(rule.matchesMarkerText("เปิดแอพ"))
        assertFalse(rule.matchesMarkerText("เปิด"))
        assertFalse(rule.matchesMarkerText("Install"))
        assertEquals("[data-mcomponent='ServerTextArea'][data-type='text']", rule.markerSelector)
        assertEquals(
            "[role='button'][data-mcomponent='MContainer'][data-type='container']",
            rule.ancestorSelector,
        )
        assertEquals(2, rule.maxAncestorDepth)
    }

    @Test
    fun `open app promo selector does not target ordinary facebook controls`() {
        val selector = engine.domRulesForUrl(profile, "https://www.facebook.com/")
            .suspiciousSelectors
            .single()

        assertFalse(selector.contains("role='button'"))
        assertFalse(selector.contains("Open app"))
        assertFalse(selector.contains("เปิดแอพ"))
        assertFalse(selector.contains("href"))
    }

    @Test
    fun `session data is preserved and third party cookies remain disabled`() {
        listOf("c_user", "xs", "datr", "fr", "presence").forEach { key ->
            assertEquals(key, null, engine.matchingSuspiciousCookiePattern(profile, key))
            assertEquals(key, null, engine.matchingSuspiciousStoragePattern(profile, key))
            assertTrue(key, profile.protectedCookieKeyPatterns.any { it.matches(key) })
            assertTrue(key, profile.protectedStorageKeyPatterns.any { it.matches(key) })
        }

        assertTrue(profile.suspiciousCookieKeyPatterns.isEmpty())
        assertTrue(profile.suspiciousStorageKeyPatterns.isEmpty())
        assertEquals(null, engine.matchingSuspiciousCookiePattern(profile, "campaign_id"))
        assertEquals(null, engine.matchingSuspiciousStoragePattern(profile, "ad_tracking_state"))
        assertFalse(profile.allowThirdPartyCookies)
        assertFalse(profile.warnOnSuspiciousNavigation)
    }
}
