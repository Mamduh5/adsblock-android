package com.example.siteshield

object DefaultProfile {
    val profile = SiteProfile(
        id = "default",
        displayName = "Generic Shield",
        startUrl = "https://www.mangakakalot.gg/",
        allowedHosts = emptyList(),
        blockedHosts = CommonRules.blockedHosts,
        suspiciousHostTokens = CommonRules.suspiciousHostTokens,
        suspiciousUrlTokens = CommonRules.suspiciousUrlTokens,
        suspiciousCookieKeyPatterns = listOf(suspiciousKeyRegex()),
        suspiciousStorageKeyPatterns = listOf(suspiciousKeyRegex()),
        domRules = CommonRules.domRules,
    )
}
