package com.example.siteshield

object DefaultProfile {
    val profile = SiteProfile(
        id = "default",
        displayName = "Generic Shield",
        startUrl = "https://www.mangakakalot.gg/",
        allowedHosts = emptyList(),
        baselinePolicy = PagePolicy(
            blockedHosts = CommonRules.blockedHosts,
            suspiciousHosts = CommonRules.suspiciousHosts,
            suspiciousUrlTokens = CommonRules.suspiciousUrlTokens,
            domRules = CommonRules.domRules,
        ),
        suspiciousCookieKeyPatterns = listOf(suspiciousKeyRegex()),
        suspiciousStorageKeyPatterns = listOf(suspiciousKeyRegex()),
        protectedCookieKeyPatterns = listOf(protectedAccountKeyRegex()),
        protectedStorageKeyPatterns = listOf(protectedAccountKeyRegex()),
    )
}
