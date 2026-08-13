package com.example.siteshield

object PalworldGgProfile {
    private val siteHost = HostPattern.DomainSuffix("palworld.gg")
    private const val optionalLocale = "(?:[a-z]{2}(?:-[a-z]{2,4})?/)?"

    private fun route(path: String): PathPattern =
        PathPattern.RegularExpression(Regex("^/$optionalLocale$path/?$"))

    private val observedRequestRules = listOf(
        RequestRule(
            id = "palworld-nitropay-entry",
            host = HostPattern.Exact("s.nitropay.com"),
            path = PathPattern.Exact("/ads-1813.js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "palworld-google-gtag",
            host = HostPattern.Exact("www.googletagmanager.com"),
            path = PathPattern.Exact("/gtag/js"),
            appliesToMainFrame = false,
        ),
        RequestRule(
            id = "palworld-cloudflare-insights",
            host = HostPattern.Exact("static.cloudflareinsights.com"),
            path = PathPattern.Prefix("/beacon.min.js/"),
            appliesToMainFrame = false,
        ),
    )

    private val baselineDomRules = DomCleanupRules(
        suspiciousSelectors = listOf(
            "script[src='https://s.nitropay.com/ads-1813.js']",
        ),
        preserveSelectors = listOf(
            "#pal-search",
            ".pal-filters",
            ".rarity-select",
            ".search-hero",
            "[role='dialog']",
            "[role='listbox']",
            "[class*='v-popper']",
        ),
    )

    private val mapDomRules = DomCleanupRules(
        preserveSelectors = listOf(
            ".game-map",
            ".map-anchor",
            ".map-container",
            ".map-panel",
            ".map-switch",
            ".ml-map",
            ".pal-chip",
            ".pal-grid",
            ".pal-search",
        ),
    )

    private val toolDomRules = DomCleanupRules(
        preserveSelectors = listOf(
            ".breeding",
            ".breed-filters",
            ".breed-pals-list",
            ".pal-filters",
            ".result",
            ".selected",
        ),
    )

    val profile = SiteProfile(
        id = "palworld-gg",
        displayName = "Palworld.gg",
        startUrl = "https://palworld.gg/",
        allowedHosts = listOf(siteHost),
        pageTypeRules = listOf(
            PageTypeRule(PageType.DETAIL, host = siteHost, path = route("pal/[^/]+")),
            PageTypeRule(PageType.INTERACTIVE_MAP, host = siteHost, path = route("map")),
            PageTypeRule(
                PageType.INTERACTIVE_TOOL,
                host = siteHost,
                path = route("breeding-calculator"),
            ),
            PageTypeRule(
                PageType.HOME_LIST_SEARCH,
                host = siteHost,
                path = PathPattern.RegularExpression(
                    Regex("^/(?:$optionalLocale)?(?:pals|items|structures|technology|tier-list|capture-rate)?/?$"),
                ),
            ),
        ),
        baselinePolicy = PagePolicy(
            blockedHosts = CommonRules.blockedHosts,
            requestRules = observedRequestRules,
            domRules = baselineDomRules,
            blockOffsiteMainFrameNavigations = false,
            promptForOffsiteMainFrameNavigations = true,
        ),
        pagePolicies = mapOf(
            PageType.INTERACTIVE_MAP to PagePolicy(domRules = mapDomRules),
            PageType.INTERACTIVE_TOOL to PagePolicy(domRules = toolDomRules),
        ),
        allowThirdPartyCookies = false,
    )
}
