package com.example.siteshield

object YouTubeProfile {
    private val youtubeHost = HostPattern.DomainSuffix("youtube.com")

    private val knownHostileHosts = listOf(
        HostPattern.DomainSuffix("popads.net"),
        HostPattern.DomainSuffix("propellerads.com"),
        HostPattern.DomainSuffix("onclickads.net"),
        HostPattern.DomainSuffix("adsterra.com"),
        HostPattern.DomainSuffix("trafficjunky.net"),
        HostPattern.DomainSuffix("exosrv.com"),
    )

    private val baselineDomRules = DomCleanupRules(
        suspiciousSelectors = listOf(
            "ytd-display-ad-renderer",
            "ytd-promoted-sparkles-web-renderer",
            "ytd-promoted-video-renderer",
            "ytd-ad-slot-renderer",
            "ytm-promoted-sparkles-web-renderer",
            "#player-ads",
            ".ytp-ad-overlay-container",
        ),
        preserveSelectors = listOf(
            "ytd-rich-item-renderer",
            "ytd-video-renderer",
            "ytd-compact-video-renderer",
            "ytd-channel-renderer",
            "ytd-playlist-renderer",
            "ytm-video-with-context-renderer",
            "ytm-compact-video-renderer",
            "ytm-reel-item-renderer",
            "#search",
            "input.searchbox-input",
        ),
        enableGenericOverlayHeuristics = false,
    )

    private val playerDomRules = DomCleanupRules(
        preserveSelectors = listOf(
            "#movie_player",
            ".html5-video-player",
            "video.html5-main-video",
            ".ytp-chrome-controls",
            ".ytp-progress-bar-container",
            ".ytp-settings-menu",
            ".ytp-caption-window-container",
            "ytd-watch-flexy",
            "ytm-watch",
            "ytm-shorts-lockup-view-model",
            "ytd-reel-video-renderer",
            "shorts-page",
            "shorts-carousel",
            "shorts-video",
            "#player-shorts-container",
            "#player-container-id",
            "#shorts-moveable-container",
            ".ytShortsCarouselCarouselWrapper",
            ".ytShortsCarouselCarouselItems",
            ".ytShortsCarouselCarouselItem",
            "ytm-engagement-panel",
            "ytw-scrim.ytWebScrimHostEngagementPanel",
        ),
    )

    val profile = SiteProfile(
        id = "youtube",
        displayName = "YouTube",
        startUrl = "https://www.youtube.com/",
        allowedHosts = listOf(
            HostPattern.Exact("youtube.com"),
            HostPattern.Exact("m.youtube.com"),
            HostPattern.Exact("youtu.be"),
        ),
        pageTypeRules = listOf(
            PageTypeRule(PageType.VIDEO_WATCH, host = youtubeHost, path = PathPattern.Exact("/watch")),
            PageTypeRule(
                PageType.VIDEO_WATCH,
                host = youtubeHost,
                path = PathPattern.RegularExpression(Regex("^/shorts/[^/]+/?$")),
            ),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = youtubeHost, path = PathPattern.Exact("/")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = youtubeHost, path = PathPattern.Exact("/results")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = youtubeHost, path = PathPattern.Exact("/playlist")),
            PageTypeRule(
                PageType.HOME_LIST_SEARCH,
                host = youtubeHost,
                path = PathPattern.RegularExpression(Regex("^/@[^/]+(?:/.*)?$")),
            ),
            PageTypeRule(
                PageType.HOME_LIST_SEARCH,
                host = youtubeHost,
                path = PathPattern.RegularExpression(Regex("^/(?:channel|c|user)/[^/]+(?:/.*)?$")),
            ),
        ),
        baselinePolicy = PagePolicy(
            blockedHosts = knownHostileHosts,
            domRules = baselineDomRules,
            blockOffsiteMainFrameNavigations = false,
            promptForOffsiteMainFrameNavigations = true,
        ),
        pagePolicies = mapOf(
            PageType.VIDEO_WATCH to PagePolicy(domRules = playerDomRules),
        ),
        dataSaverPolicy = DataSaverPolicy(
            blockNetworkImagesInMax = true,
            preserveMaxImagesForPageTypes = setOf(PageType.VIDEO_WATCH),
        ),
        protectedCookieKeyPatterns = listOf(protectedAccountKeyRegex()),
        protectedStorageKeyPatterns = listOf(protectedAccountKeyRegex()),
        allowThirdPartyCookies = false,
        warnOnSuspiciousNavigation = false,
    )
}
