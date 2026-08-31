package com.example.siteshield

object GenericWebProfile {
    val profile = SiteProfile(
        id = "generic-web",
        displayName = "Browse",
        startUrl = "about:blank",
        allowedHosts = emptyList(),
        baselinePolicy = PagePolicy(
            blockedHosts = CommonRules.blockedHosts,
            suspiciousHosts = CommonRules.suspiciousHosts,
            promptForOffsiteMainFrameNavigations = false,
            domRules = DomCleanupRules(enableGenericOverlayHeuristics = false),
        ),
        dataSaverPolicy = DataSaverPolicy(
            blockExplicitPrefetch = true,
            blockNetworkImagesInMax = true,
        ),
        adaptivePolicy = AdaptivePolicy(
            enabled = true,
            observeOffsiteNavigations = true,
            observeThirdPartyRequests = true,
            autoPromoteTypes = setOf(
                AdaptiveCandidateType.OFFSITE_REDIRECT_HOST,
                AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST,
            ),
        ),
        suspiciousCookieKeyPatterns = listOf(suspiciousKeyRegex()),
        suspiciousStorageKeyPatterns = listOf(suspiciousKeyRegex()),
        protectedCookieKeyPatterns = listOf(protectedAccountKeyRegex()),
        protectedStorageKeyPatterns = listOf(protectedAccountKeyRegex()),
    )
}
