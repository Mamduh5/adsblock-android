package com.example.siteshield

object MangakakalotProfile {
    private val siteHost = HostPattern.DomainSuffix("mangakakalot.gg")

    private val hostileRequestRules = listOf(
        RequestRule(
            id = "oundhertobeconsist-floater",
            host = HostPattern.Exact("oundhertobeconsist.org"),
            path = PathPattern.Exact("/floater"),
        ),
        RequestRule(
            id = "chubbyexemplaryhardiness-get-2090108",
            host = HostPattern.Exact("chubbyexemplaryhardiness.com"),
            path = PathPattern.Exact("/get/2090108"),
        ),
        RequestRule(
            id = "chubbyexemplaryhardiness-on-js",
            host = HostPattern.Exact("chubbyexemplaryhardiness.com"),
            path = PathPattern.Exact("/on.js"),
        ),
        RequestRule(
            id = "withagecomeswisdom-ads-info-v2",
            host = HostPattern.Exact("withagecomeswisdom.live"),
            path = PathPattern.Exact("/api/ads/get-info/v2"),
        ),
        RequestRule(
            id = "cloudfront-syxdd-1257018",
            host = HostPattern.Exact("d2dxy39sqorbhv.cloudfront.net"),
            queryTokens = listOf("syxdd=1257018"),
        ),
        RequestRule(
            id = "weiledsteverm-host",
            host = HostPattern.Exact("weiledsteverm.org"),
        ),
        RequestRule(
            id = "wbbcd-1246039-token",
            queryTokens = listOf("wbbcd=1246039"),
        ),
        RequestRule(
            id = "ghabovethec-host",
            host = HostPattern.Exact("ghabovethec.info"),
        ),
        RequestRule(
            id = "xml-oherbuttheds-popup-family",
            host = HostPattern.Exact("xml.oherbuttheds.com"),
        ),
        RequestRule(
            id = "first-party-fly-loader",
            host = siteHost,
            path = PathPattern.Exact("/js/ads/fly_e2c6a9cb8f6900e4bea0b82766581355.js"),
            firstPartyOnly = true,
        ),
        RequestRule(
            id = "first-party-clck-adu-loader",
            host = siteHost,
            path = PathPattern.Exact("/js/ads/clck-adu-kklgg.js"),
            firstPartyOnly = true,
        ),
        RequestRule(
            id = "first-party-admaven-loader",
            host = siteHost,
            path = PathPattern.Exact("/js/ads/admaven.js"),
            firstPartyOnly = true,
        ),
    )

    private val protectedAccountKeys = listOf(protectedAccountKeyRegex())

    private val suspiciousEvidenceKeys = listOf(
        suspiciousKeyRegex(),
        Regex("^__PPU_", RegexOption.IGNORE_CASE),
        Regex("^__BI_SESSION_", RegexOption.IGNORE_CASE),
        Regex("(1257018|2090108|1246039|126819)", RegexOption.IGNORE_CASE),
        Regex("^(toct1257018|toed1257018|PBFP250225|isAddHistory|UGVyc2lzdFN0b3JhZ2U)$", RegexOption.IGNORE_CASE),
    )

    private val baselineDomRules = CommonRules.domRules.copy(
        suspiciousSelectors = CommonRules.domRules.suspiciousSelectors + listOf(
            "iframe[src*='ads']",
            "iframe[src*='redirect']",
            "iframe[src*='xml.oherbuttheds.com']",
            "iframe[src*='oherbuttheds.com']",
            "a[href*='xml.oherbuttheds.com']",
            "[data-url*='xml.oherbuttheds.com']",
            "[class*='adsbox']",
            "[id*='adsbox']",
            "[class*='content-notification']",
            "[id*='content-notification']",
        ),
        suspiciousClassTokens = CommonRules.domRules.suspiciousClassTokens + listOf(
            "oherbuttheds",
            "click-catcher",
            "clickcatcher",
            "admaven",
            "ppu",
        ),
        suspiciousUrlTokens = CommonRules.domRules.suspiciousUrlTokens + listOf(
            "xml.oherbuttheds.com",
            "oherbuttheds.com",
            "oundhertobeconsist.org",
            "chubbyexemplaryhardiness.com",
            "withagecomeswisdom.live",
            "d2dxy39sqorbhv.cloudfront.net",
            "weiledsteverm.org",
            "ghabovethec.info",
            "wbbcd=1246039",
            "syxdd=1257018",
        ),
        junkTextTokens = listOf(
            "advertisement",
            "cancel",
            "close",
        ),
    )

    private val chapterDomRules = DomCleanupRules(
        suspiciousSelectors = listOf(
            ".container-chapter-reader iframe[src*='ads']",
            ".container-chapter-reader iframe[src*='redirect']",
            ".container-chapter-reader iframe[src*='xml.oherbuttheds.com']",
            ".container-chapter-reader script[src*='/js/ads/']",
            "[class*='chapter'][class*='ads']",
            "[id*='chapter'][id*='ads']",
            "[class*='reader'][class*='ads']",
            "[id*='reader'][id*='ads']",
            "[class*='notification'][class*='cancel']",
            "[id*='notification'][id*='cancel']",
        ),
        preserveSelectors = listOf(
            ".container-chapter-reader",
            ".container-chapter-reader img",
            ".container-chapter-reader picture",
            ".container-chapter-reader source",
            ".navi-change-chapter",
            ".panel-navigation",
            ".chapter-select",
            ".chapter-list",
            ".server-image",
            "#server-image",
            "select",
            "a[href*='chapter']",
            "a[href*='manga']",
            "[class*='bookmark']",
            "[id*='bookmark']",
            "[class*='comment']",
            "[id*='comment']",
            "[class*='login']",
            "[id*='login']",
            "[class*='report']",
            "[id*='report']",
        ),
        highZIndexThreshold = 900,
        overlayViewportCoverageThreshold = 0.2,
    )

    val profile = SiteProfile(
        id = "mangakakalot",
        displayName = "Mangakakalot",
        startUrl = "https://www.mangakakalot.gg/",
        allowedHosts = listOf(
            siteHost,
        ),
        pageTypeRules = listOf(
            PageTypeRule(PageType.CHAPTER_READER, host = siteHost, path = PathPattern.Prefix("/chapter/")),
            PageTypeRule(PageType.CHAPTER_READER, host = siteHost, path = PathPattern.Contains("/chapter-")),
            PageTypeRule(PageType.DETAIL, host = siteHost, path = PathPattern.Prefix("/manga/")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Exact("/")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Prefix("/manga-list")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Prefix("/genre")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Prefix("/search")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Prefix("/latest")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Prefix("/completed")),
            PageTypeRule(PageType.HOME_LIST_SEARCH, host = siteHost, path = PathPattern.Prefix("/hot-manga")),
        ),
        baselinePolicy = PagePolicy(
            blockedHosts = CommonRules.blockedHosts,
            suspiciousHosts = CommonRules.suspiciousHosts,
            suspiciousUrlTokens = CommonRules.suspiciousUrlTokens + listOf(
                "/ads",
                "/banner",
                "/pop",
                "wbbcd=1246039",
                "syxdd=1257018",
            ),
            requestRules = hostileRequestRules,
            domRules = baselineDomRules,
            blockOffsiteMainFrameNavigations = true,
            promptForOffsiteMainFrameNavigations = false,
        ),
        pagePolicies = mapOf(
            PageType.CHAPTER_READER to PagePolicy(
                domRules = chapterDomRules,
            ),
        ),
        suspiciousCookieKeyPatterns = suspiciousEvidenceKeys,
        suspiciousStorageKeyPatterns = suspiciousEvidenceKeys,
        protectedCookieKeyPatterns = protectedAccountKeys,
        protectedStorageKeyPatterns = protectedAccountKeys,
        warnOnSuspiciousNavigation = false,
    )
}
