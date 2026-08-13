package com.example.siteshield

object FacebookProfile {
    private val facebookRouteHost = HostPattern.DomainSuffix("facebook.com")

    private val knownHostileHosts = listOf(
        HostPattern.DomainSuffix("popads.net"),
        HostPattern.DomainSuffix("propellerads.com"),
        HostPattern.DomainSuffix("onclickads.net"),
        HostPattern.DomainSuffix("adsterra.com"),
        HostPattern.DomainSuffix("trafficjunky.net"),
        HostPattern.DomainSuffix("exosrv.com"),
    )

    private val sessionKeyPattern = Regex(
        pattern = "^(c_user|xs|datr|sb|fr|presence|wd|locale|dpr|ps_l|ps_n)$",
        option = RegexOption.IGNORE_CASE,
    )

    private val baselineDomRules = DomCleanupRules(
        preserveSelectors = listOf(
            "[role='feed']",
            "[role='article']",
            "[role='dialog']",
            "[role='menu']",
            "[role='listbox']",
            "form[action*='/login']",
            "input[name='email']",
            "input[name='pass']",
            "video",
        ),
        enableGenericOverlayHeuristics = false,
    )

    val profile = SiteProfile(
        id = "facebook",
        displayName = "Facebook",
        startUrl = "https://www.facebook.com/",
        allowedHosts = listOf(
            HostPattern.Exact("facebook.com"),
            HostPattern.Exact("m.facebook.com"),
            HostPattern.Exact("th-th.facebook.com"),
            HostPattern.Exact("l.facebook.com"),
        ),
        pageTypeRules = listOf(
            PageTypeRule(
                PageType.VIDEO_WATCH,
                host = facebookRouteHost,
                path = PathPattern.RegularExpression(Regex("^/(?:reel|watch)(?:/.*)?$")),
            ),
            PageTypeRule(
                PageType.VIDEO_WATCH,
                host = facebookRouteHost,
                path = PathPattern.RegularExpression(Regex("^/[^/]+/videos/[^/]+/?$")),
            ),
            PageTypeRule(PageType.DETAIL, host = facebookRouteHost, path = PathPattern.Exact("/story.php")),
            PageTypeRule(PageType.DETAIL, host = facebookRouteHost, path = PathPattern.Exact("/permalink.php")),
            PageTypeRule(PageType.DETAIL, host = facebookRouteHost, path = PathPattern.Exact("/photo.php")),
            PageTypeRule(PageType.DETAIL, host = facebookRouteHost, path = PathPattern.Exact("/profile.php")),
            PageTypeRule(
                PageType.DETAIL,
                host = facebookRouteHost,
                path = PathPattern.RegularExpression(Regex("^/[^/]+/posts/[^/]+/?$")),
            ),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = facebookRouteHost, path = PathPattern.Exact("/")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = facebookRouteHost, path = PathPattern.Exact("/home.php")),
            PageTypeRule(
                PageType.HOME_LIST_SEARCH,
                host = facebookRouteHost,
                path = PathPattern.RegularExpression(
                    Regex("^/(?:search|marketplace|notifications|menu)(?:/.*)?$"),
                ),
            ),
            PageTypeRule(
                PageType.DETAIL,
                host = facebookRouteHost,
                path = PathPattern.RegularExpression(Regex("^/[a-z0-9.]+/?$")),
            ),
        ),
        baselinePolicy = PagePolicy(
            blockedHosts = knownHostileHosts,
            domRules = baselineDomRules,
            blockOffsiteMainFrameNavigations = false,
            promptForOffsiteMainFrameNavigations = true,
        ),
        protectedCookieKeyPatterns = listOf(sessionKeyPattern),
        protectedStorageKeyPatterns = listOf(sessionKeyPattern),
        allowThirdPartyCookies = false,
        warnOnSuspiciousNavigation = false,
    )
}
