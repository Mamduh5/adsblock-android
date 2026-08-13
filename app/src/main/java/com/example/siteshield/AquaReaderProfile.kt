package com.example.siteshield

object AquaReaderProfile {
    private val siteHost = HostPattern.DomainSuffix("aquareader.org")

    private val observedRequestRules = listOf(
        RequestRule(
            id = "aquareader-aclib-ad-loader",
            host = HostPattern.Exact("acscdn.com"),
            path = PathPattern.Exact("/script/aclib.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-adsboosters-core",
            host = HostPattern.Exact("sads.adsboosters.xyz"),
            path = PathPattern.Exact("/1aa406dbd4fde738f433d131b4f5bebb.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-adsboosters-secondary",
            host = HostPattern.Exact("uads.adsboosters.xyz"),
            path = PathPattern.Exact("/"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-wombatsdiseuse-loader",
            host = HostPattern.Exact("ih.wombatsdiseuse.com"),
            path = PathPattern.Exact("/rwLvef4wC4gsoGa/134453"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-femalesfellowship-loader",
            host = HostPattern.Exact("femalesfellowship.com"),
            path = PathPattern.Exact("/31/c5/96/31c596267f82069bd8de22205c03103c.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-portalfluently-loader",
            host = HostPattern.Exact("portalfluently.com"),
            path = PathPattern.Exact("/sfp.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-grop-loader",
            host = HostPattern.Exact("grop.net"),
            path = PathPattern.Exact("/15/772fcff78656fc20abeb364d99bd15b3"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-protrafficinspector-stats",
            host = HostPattern.Exact("protrafficinspector.com"),
            path = PathPattern.Exact("/stats"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-cloudflare-insights",
            host = HostPattern.Exact("static.cloudflareinsights.com"),
            path = PathPattern.Prefix("/beacon.min.js/"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-hdbkome-reader-ad",
            host = HostPattern.Exact("hdbkome.com"),
            path = PathPattern.Exact("/0yy4q052.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-google-gtag",
            host = HostPattern.Exact("www.googletagmanager.com"),
            path = PathPattern.Exact("/gtag/js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-google-gtm",
            host = HostPattern.Exact("www.googletagmanager.com"),
            path = PathPattern.Exact("/gtm.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "aquareader-google-analytics-collect",
            host = HostPattern.Exact("www.google-analytics.com"),
            path = PathPattern.Exact("/g/collect"),
            appliesToMainFrame = false,
        ),
    )

    private val baselineDomRules = DomCleanupRules(
        suspiciousSelectors = listOf(
            "script[src='https://acscdn.com/script/aclib.js']",
            "script[src*='adsboosters.xyz']",
            "script[src*='wombatsdiseuse.com/rwLvef4wC4gsoGa/134453']",
            "script[src*='femalesfellowship.com/31/c5/96/31c596267f82069bd8de22205c03103c.js']",
            "script[src='https://portalfluently.com/sfp.js']",
            "script[src*='grop.net/15/772fcff78656fc20abeb364d99bd15b3']",
            ".maai-ad",
        ),
        preserveSelectors = listOf(
            "form[role='search']",
            "input[type='search']",
            "[class*='manga']",
            "[class*='chapter']",
            "[class*='bookmark']",
            "[class*='pagination']",
        ),
    )

    private val readerDomRules = DomCleanupRules(
        preserveSelectors = listOf(
            ".reading-content",
            ".page-break",
            ".wp-manga-chapter-img",
            ".aqua-reader-topbar",
            ".aqua-reader-bottombar",
            ".aqua-chapter-panel",
            ".chapter-select",
            "select",
            "a[href*='/chapter-']",
            "a[href*='/manga/']",
        ),
    )

    val profile = SiteProfile(
        id = "aquareader",
        displayName = "AquaReader",
        startUrl = "https://aquareader.org/",
        allowedHosts = listOf(siteHost),
        pageTypeRules = listOf(
            PageTypeRule(
                PageType.CHAPTER_READER,
                host = siteHost,
                path = PathPattern.RegularExpression(
                    Regex("^/manga/([^/]+)/(?:\\1/)?chapter-[^/]+/?$"),
                ),
            ),
            PageTypeRule(
                PageType.DETAIL,
                host = siteHost,
                path = PathPattern.RegularExpression(Regex("^/manga/[^/]+/?$")),
            ),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Exact("/")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Exact("/manga/")),
            PageTypeRule(
                PageType.HOME_LIST_SEARCH,
                host = siteHost,
                path = PathPattern.RegularExpression(Regex("^/page/[0-9]+/?$")),
            ),
        ),
        baselinePolicy = PagePolicy(
            blockedHosts = CommonRules.blockedHosts,
            requestRules = observedRequestRules,
            domRules = baselineDomRules,
            blockOffsiteMainFrameNavigations = true,
            promptForOffsiteMainFrameNavigations = false,
        ),
        pagePolicies = mapOf(
            PageType.CHAPTER_READER to PagePolicy(domRules = readerDomRules),
        ),
        allowThirdPartyCookies = false,
        warnOnSuspiciousNavigation = false,
    )
}
