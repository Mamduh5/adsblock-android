package com.example.siteshield

import java.net.URI
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

    fun isBlockedHost(profile: SiteProfile, host: String?): Boolean {
        val normalizedHost = host.normalizedHost() ?: return false
        return profile.blockedHosts.any { it.matches(normalizedHost) } ||
            profile.suspiciousHostTokens.any { normalizedHost.contains(it.lowercase(Locale.US)) }
    }

    fun isSuspiciousNavigation(profile: SiteProfile, url: String): Boolean {
        val parsed = parseUrl(url) ?: return true
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return true
        if (isBlockedHost(profile, parsed.host)) return true
        return containsSuspiciousUrlToken(profile, url)
    }

    fun isBlockedResource(profile: SiteProfile, url: String): Boolean {
        val parsed = parseUrl(url) ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return false
        if (isBlockedHost(profile, parsed.host)) return true
        return containsSuspiciousUrlToken(profile, url)
    }

    fun isSuspiciousCookieKey(profile: SiteProfile, key: String): Boolean =
        profile.suspiciousCookieKeyPatterns.any { it.containsMatchIn(key) }

    fun isSuspiciousStorageKey(profile: SiteProfile, key: String): Boolean =
        profile.suspiciousStorageKeyPatterns.any { it.containsMatchIn(key) }

    private fun containsSuspiciousUrlToken(profile: SiteProfile, url: String): Boolean {
        val lowerUrl = url.lowercase(Locale.US)
        return profile.suspiciousUrlTokens.any { lowerUrl.contains(it.lowercase(Locale.US)) }
    }

    private fun parseUrl(url: String): URI? =
        runCatching { URI(url) }.getOrNull()
}
