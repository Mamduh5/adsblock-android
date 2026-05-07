package com.example.siteshield

import java.net.URI
import java.util.Locale

data class SiteProfile(
    val id: String,
    val displayName: String,
    val startUrl: String,
    val allowedHosts: List<HostPattern>,
    val blockedHosts: List<HostPattern> = emptyList(),
    val suspiciousHostTokens: List<String> = emptyList(),
    val suspiciousUrlTokens: List<String> = emptyList(),
    val suspiciousCookieKeyPatterns: List<Regex> = emptyList(),
    val suspiciousStorageKeyPatterns: List<Regex> = emptyList(),
    val domRules: DomCleanupRules = DomCleanupRules(),
    val allowThirdPartyCookies: Boolean = false,
    val warnOnSuspiciousNavigation: Boolean = AppConstants.WarnOnSuspiciousNavigation,
)

data class DomCleanupRules(
    val suspiciousSelectors: List<String> = emptyList(),
    val suspiciousClassTokens: List<String> = emptyList(),
    val suspiciousUrlTokens: List<String> = emptyList(),
    val baitTextTokens: List<String> = emptyList(),
    val highZIndexThreshold: Int = 999,
    val overlayViewportCoverageThreshold: Double = 0.28,
)

sealed class HostPattern {
    abstract fun matches(host: String?): Boolean

    data class Exact(private val host: String) : HostPattern() {
        private val normalized = host.normalizedHost()

        override fun matches(host: String?): Boolean = host.normalizedHost() == normalized
    }

    data class DomainSuffix(private val domain: String) : HostPattern() {
        private val normalized = domain.normalizedHost()

        override fun matches(host: String?): Boolean {
            val normalizedHost = host.normalizedHost() ?: return false
            return normalizedHost == normalized || normalizedHost.endsWith(".$normalized")
        }
    }

    data class Contains(private val token: String) : HostPattern() {
        private val normalized = token.lowercase(Locale.US)

        override fun matches(host: String?): Boolean =
            host.normalizedHost()?.contains(normalized) == true
    }
}

fun String?.normalizedHost(): String? =
    this
        ?.lowercase(Locale.US)
        ?.trim()
        ?.trimEnd('.')
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }

fun String.hostFromUrl(): String? =
    runCatching { URI(this).host }
        .getOrNull()
        .normalizedHost()

fun suspiciousKeyRegex(): Regex = Regex(
    pattern = "(^|[-_.])(ad|ads|popup|redirect|interstitial|promo|campaign)([-_.]|$)|" +
        "(adid|ad_id|adsid|popup|redirect|interstitial|promo|campaign)",
    option = RegexOption.IGNORE_CASE,
)
