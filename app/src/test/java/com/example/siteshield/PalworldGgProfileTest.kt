package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalworldGgProfileTest {
    private val engine = GenericBlockerEngine()
    private val profile = PalworldGgProfile.profile

    @Test
    fun `classifies primary and localized routes`() {
        mapOf(
            "https://palworld.gg/" to PageType.HOME_LIST_SEARCH,
            "https://palworld.gg/pals" to PageType.HOME_LIST_SEARCH,
            "https://palworld.gg/pal/anubis" to PageType.DETAIL,
            "https://palworld.gg/map" to PageType.INTERACTIVE_MAP,
            "https://palworld.gg/breeding-calculator" to PageType.INTERACTIVE_TOOL,
            "https://palworld.gg/tier-list" to PageType.HOME_LIST_SEARCH,
            "https://palworld.gg/de/pals" to PageType.HOME_LIST_SEARCH,
            "https://palworld.gg/pt-BR/map" to PageType.INTERACTIVE_MAP,
            "https://palworld.gg/ja/breeding-calculator" to PageType.INTERACTIVE_TOOL,
            "https://palworld.gg/zh-Hans/pal/anubis" to PageType.DETAIL,
            "https://palworld.gg/unrecognized-route" to PageType.UNKNOWN,
        ).forEach { (url, expected) ->
            assertEquals(url, expected, engine.classifyPageType(profile, url))
        }
    }

    @Test
    fun `navigation allows same site blocks known hostile and prompts ordinary external`() {
        val currentUrl = "https://palworld.gg/pals"

        assertEquals(
            BlockDecision.Allow,
            engine.navigationDecision(profile, "https://www.palworld.gg/pal/anubis", currentUrl),
        )
        assertEquals(
            BlockDecision.Block(BlockReason.BLOCKED_HOST),
            engine.navigationDecision(profile, "https://ads.doubleclick.net/click", currentUrl),
        )
        assertEquals(
            BlockDecision.PromptExternal("https://pocketpair.jp/news"),
            engine.navigationDecision(profile, "https://pocketpair.jp/news", currentUrl),
        )
    }

    @Test
    fun `observed advertising and analytics endpoints are blocked precisely`() {
        val currentUrl = "https://palworld.gg/map"
        val cases = mapOf(
            "https://s.nitropay.com/ads-1813.js" to "palworld-nitropay-entry",
            "https://www.googletagmanager.com/gtag/js?id=G-RLND6P1RWL" to "palworld-google-gtag",
            "https://static.cloudflareinsights.com/beacon.min.js/v123" to "palworld-cloudflare-insights",
        )

        cases.forEach { (url, ruleId) ->
            assertEquals(
                BlockDecision.Block(BlockReason.REQUEST_RULE, ruleId),
                engine.resourceDecision(profile, url, currentUrl),
            )
        }
    }

    @Test
    fun `nearby required and lookalike resources remain allowed`() {
        val currentUrl = "https://palworld.gg/breeding-calculator"
        listOf(
            "https://palworld.gg/_nuxt/bFJ2Tpnq.js",
            "https://palworld.gg/breeding-calculator/_payload.json?build=current",
            "https://s.nitropay.com/site-app.js",
            "https://www.googletagmanager.com/required-app.js",
            "https://static.cloudflareinsights.com/app.js",
            "https://palbreed.com/",
        ).forEach { url ->
            assertEquals(url, BlockDecision.Allow, engine.resourceDecision(profile, url, currentUrl))
        }
    }

    @Test
    fun `interactive page policies preserve observed controls`() {
        val mapRules = engine.domRulesForUrl(profile, "https://palworld.gg/pt-BR/map")
        val toolRules = engine.domRulesForUrl(profile, "https://palworld.gg/ja/breeding-calculator")

        assertTrue(mapRules.preserveSelectors.contains(".game-map"))
        assertTrue(mapRules.preserveSelectors.contains(".map-panel"))
        assertTrue(mapRules.preserveSelectors.contains(".ml-map"))
        assertTrue(toolRules.preserveSelectors.contains(".breeding"))
        assertTrue(toolRules.preserveSelectors.contains(".breed-pals-list"))
        assertFalse(mapRules.suspiciousSelectors.contains(".map-panel"))
    }
}
