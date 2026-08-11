package com.example.siteshield

import java.util.Locale

enum class ProfileRequestContext {
    MAIN_FRAME_NAVIGATION,
    SUBFRAME_NAVIGATION,
    SUBRESOURCE,
}

enum class BlockReason {
    MALFORMED_URL,
    UNSUPPORTED_SCHEME,
    REQUEST_RULE,
    BLOCKED_HOST,
    SUSPICIOUS_HOST,
    SUSPICIOUS_URL,
    OFFSITE_MAIN_FRAME,
}

sealed interface BlockDecision {
    data object Allow : BlockDecision

    data class Block(
        val reason: BlockReason,
        val ruleId: String? = null,
    ) : BlockDecision

    data class PromptExternal(val url: String) : BlockDecision
}

class GenericBlockerEngine(
    private val profileCatalog: SiteProfileCatalog = SiteProfileRegistry.catalog,
) {
    fun profileForTopLevelUrl(url: String?, currentProfile: SiteProfile? = null): SiteProfile {
        val matched = profileCatalog.match(url)
        return when {
            matched.id != profileCatalog.defaultProfile.id -> matched
            currentProfile != null -> currentProfile
            else -> matched
        }
    }

    fun profileForRequest(
        url: String?,
        activeTopLevelProfile: SiteProfile,
        context: ProfileRequestContext,
    ): SiteProfile =
        when (context) {
            ProfileRequestContext.MAIN_FRAME_NAVIGATION ->
                profileForTopLevelUrl(url, activeTopLevelProfile)
            ProfileRequestContext.SUBFRAME_NAVIGATION -> activeTopLevelProfile
            ProfileRequestContext.SUBRESOURCE -> activeTopLevelProfile
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

    fun describePolicy(profile: SiteProfile, pageType: PageType): String {
        val policy = policyForPageType(profile, pageType)
        return "profile=${profile.id}, pageType=$pageType, blockedHosts=${policy.blockedHosts.size}, " +
            "suspiciousHosts=${policy.suspiciousHosts.size}, urlTokens=${policy.suspiciousUrlTokens.size}, " +
            "requestRules=${policy.requestRules.size}, offsiteMainFrameDenied=${policy.blockOffsiteMainFrameNavigations}, " +
            "offsitePrompt=${policy.promptForOffsiteMainFrameNavigations}"
    }

    fun isBlockedHost(profile: SiteProfile, host: String?, pageType: PageType = PageType.UNKNOWN): Boolean {
        val policy = policyForPageType(profile, pageType)
        return hostBlockDecision(policy, host) != null
    }

    fun navigationDecision(
        profile: SiteProfile,
        url: String,
        currentPageUrl: String? = url,
        isMainFrame: Boolean = true,
    ): BlockDecision {
        val parsed = parseUrl(url) ?: return BlockDecision.Block(BlockReason.MALFORMED_URL)
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) {
            return BlockDecision.Block(BlockReason.UNSUPPORTED_SCHEME)
        }

        val currentPageType = classifyPageType(profile, currentPageUrl)
        val policy = policyForPageType(profile, currentPageType)
        val matchedRule = matchingRequestRule(policy, profile, url, isMainFrame)
        if (matchedRule != null) {
            return BlockDecision.Block(BlockReason.REQUEST_RULE, matchedRule.id)
        }
        hostBlockDecision(policy, parsed.host)?.let { return it }
        if (containsSuspiciousUrlToken(policy, url)) {
            return BlockDecision.Block(BlockReason.SUSPICIOUS_URL)
        }
        if (
            isMainFrame &&
            policy.blockOffsiteMainFrameNavigations &&
            !isAllowedHost(profile, parsed.host)
        ) {
            return BlockDecision.Block(BlockReason.OFFSITE_MAIN_FRAME)
        }
        if (
            isMainFrame &&
            policy.promptForOffsiteMainFrameNavigations &&
            !isAllowedHost(profile, parsed.host)
        ) {
            return BlockDecision.PromptExternal(url)
        }
        return BlockDecision.Allow
    }

    fun isSuspiciousNavigation(
        profile: SiteProfile,
        url: String,
        currentPageUrl: String? = url,
        isMainFrame: Boolean = true,
    ): Boolean = navigationDecision(profile, url, currentPageUrl, isMainFrame) is BlockDecision.Block

    fun resourceDecision(
        profile: SiteProfile,
        url: String,
        currentPageUrl: String? = null,
    ): BlockDecision {
        val parsed = parseUrl(url) ?: return BlockDecision.Allow
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return BlockDecision.Allow

        val currentPageType = classifyPageType(profile, currentPageUrl ?: url)
        val policy = policyForPageType(profile, currentPageType)
        val matchedRule = matchingRequestRule(policy, profile, url, isMainFrame = false)
        if (matchedRule != null) {
            return BlockDecision.Block(BlockReason.REQUEST_RULE, matchedRule.id)
        }
        hostBlockDecision(policy, parsed.host)?.let { return it }
        if (containsSuspiciousUrlToken(policy, url)) {
            return BlockDecision.Block(BlockReason.SUSPICIOUS_URL)
        }
        return BlockDecision.Allow
    }

    fun isBlockedResource(profile: SiteProfile, url: String, currentPageUrl: String? = null): Boolean =
        resourceDecision(profile, url, currentPageUrl) is BlockDecision.Block

    fun matchingRequestRule(
        profile: SiteProfile,
        url: String,
        currentPageUrl: String?,
        isMainFrame: Boolean,
    ): RequestRule? {
        val pageType = classifyPageType(profile, currentPageUrl ?: url)
        return matchingRequestRule(policyForPageType(profile, pageType), profile, url, isMainFrame)
    }

    fun matchingSuspiciousCookiePattern(profile: SiteProfile, key: String): Regex? =
        profile.suspiciousCookieKeyPatterns.firstOrNull { suspiciousPattern ->
            suspiciousPattern.containsMatchIn(key) &&
                profile.protectedCookieKeyPatterns.none { it.containsMatchIn(key) }
        }

    fun matchingSuspiciousStoragePattern(profile: SiteProfile, key: String): Regex? =
        profile.suspiciousStorageKeyPatterns.firstOrNull { suspiciousPattern ->
            suspiciousPattern.containsMatchIn(key) &&
                profile.protectedStorageKeyPatterns.none { it.containsMatchIn(key) }
        }

    fun isSuspiciousCookieKey(profile: SiteProfile, key: String): Boolean =
        matchingSuspiciousCookiePattern(profile, key) != null

    fun isSuspiciousStorageKey(profile: SiteProfile, key: String): Boolean =
        matchingSuspiciousStoragePattern(profile, key) != null

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

    private fun hostBlockDecision(policy: PagePolicy, host: String?): BlockDecision.Block? {
        val normalizedHost = host.normalizedHost() ?: return null
        if (policy.blockedHosts.any { it.matches(normalizedHost) }) {
            return BlockDecision.Block(BlockReason.BLOCKED_HOST)
        }
        if (policy.suspiciousHosts.any { it.matches(normalizedHost) }) {
            return BlockDecision.Block(BlockReason.SUSPICIOUS_HOST)
        }
        return null
    }

    private fun matchingRequestRule(
        policy: PagePolicy,
        profile: SiteProfile,
        url: String,
        isMainFrame: Boolean,
    ): RequestRule? =
        policy.requestRules.firstOrNull { it.matches(url, profile, isMainFrame) }

    private fun parseUrl(url: String) = url.toUriOrNull()
}
