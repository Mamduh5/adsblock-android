package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeProfileTest {
    private val engine = GenericBlockerEngine()
    private val profile = YouTubeProfile.profile

    @Test
    fun `classifies observed anonymous routes`() {
        mapOf(
            "https://www.youtube.com/" to PageType.HOME_LIST_SEARCH,
            "https://m.youtube.com/" to PageType.HOME_LIST_SEARCH,
            "https://www.youtube.com/results?search_query=palworld" to PageType.HOME_LIST_SEARCH,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to PageType.VIDEO_WATCH,
            "https://m.youtube.com/shorts/aqz-KE-bpKQ" to PageType.VIDEO_WATCH,
            "https://www.youtube.com/playlist?list=PLexample" to PageType.HOME_LIST_SEARCH,
            "https://www.youtube.com/@YouTube/videos" to PageType.HOME_LIST_SEARCH,
            "https://www.youtube.com/channel/UCexample" to PageType.HOME_LIST_SEARCH,
            "https://www.youtube.com/feed/history" to PageType.UNKNOWN,
            "https://www.youtube.com/shorts/" to PageType.UNKNOWN,
            "https://www.youtube.com/watchlater" to PageType.UNKNOWN,
            "https://youtu.be/dQw4w9WgXcQ" to PageType.UNKNOWN,
        ).forEach { (url, expected) ->
            assertEquals(url, expected, engine.classifyPageType(profile, url))
        }
    }

    @Test
    fun `navigation allows youtube blocks known ad hosts and prompts ordinary external links`() {
        val currentUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

        assertEquals(
            BlockDecision.Allow,
            engine.navigationDecision(profile, "https://m.youtube.com/results?search_query=music", currentUrl),
        )
        assertEquals(
            BlockDecision.Allow,
            engine.navigationDecision(profile, "https://youtu.be/dQw4w9WgXcQ", currentUrl),
        )
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
    fun `allows nearby app player media thumbnail caption and api resources`() {
        val currentUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/s/player/abcd/player_ias.vflset/en_US/base.js",
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            "https://www.youtube.com/api/stats/qoe?event=streamingstats",
            "https://www.youtube.com/pageadvice/help.js",
            "https://www.youtube.com/pagead/1p-user-list/962985656/",
            "https://googleads.g.doubleclick.net/pagead/id",
            "https://static.doubleclick.net/instream/ad_status.js",
            "https://rr1---sn.example.googlevideo.com/videoplayback?id=content",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&lang=en",
            "https://fonts.googleapis.com/css2?family=Roboto",
        ).forEach { url ->
            assertEquals(url, BlockDecision.Allow, engine.resourceDecision(profile, url, currentUrl))
        }
    }

    @Test
    fun `watch cleanup targets separate ad ui and protects playback`() {
        val rules = engine.domRulesForUrl(profile, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertTrue(rules.suspiciousSelectors.contains("ytd-ad-slot-renderer"))
        assertTrue(rules.suspiciousSelectors.contains(".ytp-ad-overlay-container"))
        assertTrue(rules.preserveSelectors.contains("#movie_player"))
        assertTrue(rules.preserveSelectors.contains("video.html5-main-video"))
        assertTrue(rules.preserveSelectors.contains("ytd-compact-video-renderer"))
        assertTrue(rules.preserveSelectors.contains("shorts-page"))
        assertTrue(rules.preserveSelectors.contains("#player-container-id"))
        assertTrue(rules.preserveSelectors.contains("ytm-engagement-panel"))
        assertTrue(rules.preserveSelectors.contains("ytw-scrim.ytWebScrimHostEngagementPanel"))
        assertFalse(rules.suspiciousSelectors.contains("ytd-video-renderer"))
        assertFalse(rules.enableGenericOverlayHeuristics)
        assertFalse(profile.allowThirdPartyCookies)
        assertFalse(profile.warnOnSuspiciousNavigation)
    }
}
