package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AquaReaderProfileTest {
    private val engine = GenericBlockerEngine()
    private val profile = AquaReaderProfile.profile

    @Test
    fun `classifies observed routes without treating nearby paths as chapters`() {
        mapOf(
            "https://aquareader.org/" to PageType.HOME_LIST_SEARCH,
            "https://aquareader.org/?s=eleceed" to PageType.HOME_LIST_SEARCH,
            "https://aquareader.org/manga/" to PageType.HOME_LIST_SEARCH,
            "https://aquareader.org/manga/?s=eleceed&status=ongoing" to PageType.HOME_LIST_SEARCH,
            "https://aquareader.org/page/5/" to PageType.HOME_LIST_SEARCH,
            "https://aquareader.org/manga/hello-mr-veterinarian/" to PageType.DETAIL,
            "https://aquareader.org/manga/hello-mr-veterinarian/chapter-1/" to PageType.CHAPTER_READER,
            "https://aquareader.org/manga/hello-mr-veterinarian/hello-mr-veterinarian/chapter-2/" to
                PageType.CHAPTER_READER,
            "https://aquareader.org/manga/example/chapter-9.5/" to PageType.CHAPTER_READER,
            "https://aquareader.org/manga/example/not-example/chapter-2/" to PageType.UNKNOWN,
            "https://aquareader.org/manga/example/chapters/" to PageType.UNKNOWN,
            "https://aquareader.org/manga/example/chapter/1/" to PageType.UNKNOWN,
            "https://aquareader.org/user/example/" to PageType.UNKNOWN,
        ).forEach { (url, expected) ->
            assertEquals(url, expected, engine.classifyPageType(profile, url))
        }
    }

    @Test
    fun `navigation allows same site and silently blocks offsite destinations`() {
        val currentUrl = "https://aquareader.org/manga/hello-mr-veterinarian/chapter-1/"

        assertFalse(profile.warnOnSuspiciousNavigation)

        assertEquals(
            BlockDecision.Allow,
            engine.navigationDecision(profile, "https://www.aquareader.org/manga/", currentUrl),
        )
        assertEquals(
            BlockDecision.Block(BlockReason.BLOCKED_HOST),
            engine.navigationDecision(profile, "https://ads.doubleclick.net/click", currentUrl),
        )
        assertEquals(
            BlockDecision.Block(BlockReason.OFFSITE_MAIN_FRAME),
            engine.navigationDecision(profile, "https://example.org/about", currentUrl),
        )
    }

    @Test
    fun `observed advertising and tracking resources are blocked precisely`() {
        val currentUrl = "https://aquareader.org/manga/hello-mr-veterinarian/chapter-1/"
        val cases = mapOf(
            "https://acscdn.com/script/aclib.js" to "aquareader-aclib-ad-loader",
            "https://sads.adsboosters.xyz/1aa406dbd4fde738f433d131b4f5bebb.js" to "aquareader-adsboosters-core",
            "https://uads.adsboosters.xyz/" to "aquareader-adsboosters-secondary",
            "https://ih.wombatsdiseuse.com/rwLvef4wC4gsoGa/134453" to "aquareader-wombatsdiseuse-loader",
            "https://femalesfellowship.com/31/c5/96/31c596267f82069bd8de22205c03103c.js" to
                "aquareader-femalesfellowship-loader",
            "https://portalfluently.com/sfp.js" to "aquareader-portalfluently-loader",
            "https://grop.net/15/772fcff78656fc20abeb364d99bd15b3" to "aquareader-grop-loader",
            "https://protrafficinspector.com/stats" to "aquareader-protrafficinspector-stats",
            "https://static.cloudflareinsights.com/beacon.min.js/v123" to "aquareader-cloudflare-insights",
            "https://hdbkome.com/0yy4q052.js" to "aquareader-hdbkome-reader-ad",
            "https://www.googletagmanager.com/gtag/js?id=GT-P8QQQJLN" to "aquareader-google-gtag",
            "https://www.googletagmanager.com/gtm.js?id=GTM-P8D25CKW" to "aquareader-google-gtm",
            "https://www.google-analytics.com/g/collect?v=2" to "aquareader-google-analytics-collect",
        )

        cases.forEach { (url, ruleId) ->
            assertEquals(
                BlockDecision.Block(BlockReason.REQUEST_RULE, ruleId),
                engine.resourceDecision(profile, url, currentUrl),
            )
        }
    }

    @Test
    fun `nearby and required resources remain allowed`() {
        val currentUrl = "https://aquareader.org/manga/hello-mr-veterinarian/chapter-1/"
        listOf(
            "https://acscdn.com/script/application.js",
            "https://sads.adsboosters.xyz/site.js",
            "https://uads.adsboosters.xyz/app.js",
            "https://ih.wombatsdiseuse.com/rwLvef4wC4gsoGa/134454",
            "https://femalesfellowship.com/application.js",
            "https://portalfluently.com/application.js",
            "https://grop.net/15/application.js",
            "https://protrafficinspector.com/application.js",
            "https://static.cloudflareinsights.com/application.js",
            "https://hdbkome.com/application.js",
            "https://www.googletagmanager.com/application.js",
            "https://www.google-analytics.com/application.js",
            "https://accounts.google.com/gsi/client",
            "https://fonts.googleapis.com/css2?family=Poppins",
            "https://aquareader.org/wp-includes/js/jquery/jquery.min.js?ver=3.7.1",
            "https://aquareader.org/wp-content/uploads/chapter/page-001.webp",
        ).forEach { url ->
            assertEquals(url, BlockDecision.Allow, engine.resourceDecision(profile, url, currentUrl))
        }
    }

    @Test
    fun `reader policy protects content and navigation selectors`() {
        val rules = engine.domRulesForUrl(
            profile,
            "https://aquareader.org/manga/hello-mr-veterinarian/chapter-1/",
        )

        assertTrue(rules.preserveSelectors.contains(".wp-manga-chapter-img"))
        assertTrue(rules.preserveSelectors.contains("a[href*='/chapter-']"))
        assertTrue(rules.preserveSelectors.contains("select"))
        assertFalse(rules.suspiciousSelectors.contains("[class*='chapter']"))
        assertTrue(rules.suspiciousSelectors.contains("script[src*='adsboosters.xyz']"))
        assertTrue(rules.suspiciousSelectors.contains(".maai-ad"))
    }
}
