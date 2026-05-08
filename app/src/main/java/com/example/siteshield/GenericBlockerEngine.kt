package com.example.siteshield

import java.util.Locale

class GenericBlockerEngine(private val registry: SiteProfileRegistry = SiteProfileRegistry) {
    fun profileForUrl(url: String?, preferredProfile: SiteProfile? = null): SiteProfile {
        val matched = registry.match(url)
        return when {
            matched.id != registry.defaultProfile.id -> matched
            preferredProfile != null -> preferredProfile
            else -> matched
        }
    }

    fun isAllowedHost(profile: SiteProfile, host: String?): Boolean =
        profile.allowedHosts.isEmpty() || profile.allowedHosts.any { it.matches(host) }

    fun classifyPageType(profile: SiteProfile, url: String?): PageType {
        val targetUrl = url ?: return PageType.UNKNOWN
        return profile.pageTypeRules
            .firstOrNull { it.matches(targetUrl) }
            ?.pageType
            ?: PageType.UNKNOWN
    }

    fun policyForPageType(profile: SiteProfile, pageType: PageType): PagePolicy =
        profile.baselinePolicy.mergedWith(profile.pagePolicies[pageType] ?: PagePolicy())

    fun policyForUrl(profile: SiteProfile, url: String?): PagePolicy =
        policyForPageType(profile, classifyPageType(profile, url))

    fun domRulesForUrl(profile: SiteProfile, url: String?): DomCleanupRules =
        policyForUrl(profile, url).domRules

    fun isBlockedHost(profile: SiteProfile, host: String?, pageType: PageType = PageType.UNKNOWN): Boolean {
        val normalizedHost = host.normalizedHost() ?: return false
        val policy = policyForPageType(profile, pageType)
        return policy.blockedHosts.any { it.matches(normalizedHost) } ||
            policy.suspiciousHostTokens.any { normalizedHost.contains(it.lowercase(Locale.US)) }
    }

    fun isSuspiciousNavigation(
        profile: SiteProfile,
        url: String,
        currentPageUrl: String? = url,
        isMainFrame: Boolean = true,
    ): Boolean {
        val parsed = parseUrl(url) ?: return true
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return true

        val currentPageType = classifyPageType(profile, currentPageUrl)
        val policy = policyForPageType(profile, currentPageType)
        if (policy.requestRules.any { it.matches(url, profile, isMainFrame) }) return true
        if (isBlockedHost(profile, parsed.host, currentPageType)) return true
        if (containsSuspiciousUrlToken(policy, url)) return true

        return isMainFrame &&
            policy.blockOffsiteMainFrameNavigations &&
            !isAllowedHost(profile, parsed.host)
    }

    fun isBlockedResource(profile: SiteProfile, url: String, currentPageUrl: String? = null): Boolean {
        val parsed = parseUrl(url) ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return false
        val currentPageType = classifyPageType(profile, currentPageUrl ?: url)
        val policy = policyForPageType(profile, currentPageType)
        if (policy.requestRules.any { it.matches(url, profile, isMainFrame = false) }) return true
        if (isBlockedHost(profile, parsed.host, currentPageType)) return true
        return containsSuspiciousUrlToken(policy, url)
    }

    fun isSuspiciousCookieKey(profile: SiteProfile, key: String): Boolean =
        profile.protectedCookieKeyPatterns.none { it.containsMatchIn(key) } &&
            profile.suspiciousCookieKeyPatterns.any { it.containsMatchIn(key) }

    fun isSuspiciousStorageKey(profile: SiteProfile, key: String): Boolean =
        profile.protectedStorageKeyPatterns.none { it.containsMatchIn(key) } &&
            profile.suspiciousStorageKeyPatterns.any { it.containsMatchIn(key) }

    fun shouldPromptForOffsiteMainFrameNavigation(
        profile: SiteProfile,
        targetUrl: String,
        currentPageUrl: String?,
    ): Boolean {
        val parsed = parseUrl(targetUrl) ?: return false
        val policy = policyForUrl(profile, currentPageUrl)
        return policy.promptForOffsiteMainFrameNavigations && !isAllowedHost(profile, parsed.host)
    }

    private fun containsSuspiciousUrlToken(policy: PagePolicy, url: String): Boolean {
        val lowerUrl = url.lowercase(Locale.US)
        return policy.suspiciousUrlTokens.any { lowerUrl.contains(it.lowercase(Locale.US)) }
    }

    private fun parseUrl(url: String) = url.toUriOrNull()
}
