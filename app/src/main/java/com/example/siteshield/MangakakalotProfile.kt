package com.example.siteshield

object MangakakalotProfile {
    val profile = SiteProfile(
        id = "mangakakalot",
        displayName = "Mangakakalot",
        startUrl = "https://www.mangakakalot.gg/",
        allowedHosts = listOf(
            HostPattern.DomainSuffix("mangakakalot.gg"),
        ),
        blockedHosts = CommonRules.blockedHosts,
        suspiciousHostTokens = CommonRules.suspiciousHostTokens,
        suspiciousUrlTokens = CommonRules.suspiciousUrlTokens + listOf(
            "/ads",
            "/banner",
            "/pop",
        ),
        suspiciousCookieKeyPatterns = listOf(suspiciousKeyRegex()),
        suspiciousStorageKeyPatterns = listOf(suspiciousKeyRegex()),
        domRules = CommonRules.domRules.copy(
            suspiciousSelectors = CommonRules.domRules.suspiciousSelectors + listOf(
                "iframe[src*='ads']",
                "iframe[src*='redirect']",
                "[class*='adsbox']",
                "[id*='adsbox']",
            ),
        ),
    )
}
