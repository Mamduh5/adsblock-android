package com.example.siteshield

import java.net.URI
import java.util.Locale

data class SiteProfile(
    val id: String,
    val displayName: String,
    val startUrl: String,
    val allowedHosts: List<HostPattern>,
    val pageTypeRules: List<PageTypeRule> = emptyList(),
    val baselinePolicy: PagePolicy = PagePolicy(),
    val pagePolicies: Map<PageType, PagePolicy> = emptyMap(),
    val suspiciousCookieKeyPatterns: List<Regex> = emptyList(),
    val suspiciousStorageKeyPatterns: List<Regex> = emptyList(),
    val protectedCookieKeyPatterns: List<Regex> = emptyList(),
    val protectedStorageKeyPatterns: List<Regex> = emptyList(),
    val allowThirdPartyCookies: Boolean = false,
    val warnOnSuspiciousNavigation: Boolean = AppConstants.WarnOnSuspiciousNavigation,
)

enum class PageType {
    HOME_LIST_SEARCH,
    DETAIL,
    CHAPTER_READER,
    INTERACTIVE_MAP,
    INTERACTIVE_TOOL,
    VIDEO_WATCH,
    UNKNOWN,
}

data class PageTypeRule(
    val pageType: PageType,
    val host: HostPattern? = null,
    val path: PathPattern? = null,
    val queryTokens: List<String> = emptyList(),
) {
    fun matches(url: String): Boolean {
        val parsed = url.toUriOrNull() ?: return false
        val normalizedPath = parsed.path.orEmpty().ifBlank { "/" }
        val normalizedQuery = parsed.rawQuery.orEmpty().lowercase(Locale.US)
        return (host == null || host.matches(parsed.host)) &&
            (path == null || path.matches(normalizedPath)) &&
            queryTokens.all { normalizedQuery.contains(it.lowercase(Locale.US)) }
    }
}

data class PagePolicy(
    val blockedHosts: List<HostPattern> = emptyList(),
    val suspiciousHosts: List<HostPattern> = emptyList(),
    val suspiciousUrlTokens: List<String> = emptyList(),
    val requestRules: List<RequestRule> = emptyList(),
    val domRules: DomCleanupRules = DomCleanupRules(),
    val blockOffsiteMainFrameNavigations: Boolean = false,
    val promptForOffsiteMainFrameNavigations: Boolean = true,
) {
    fun mergedWith(override: PagePolicy): PagePolicy =
        PagePolicy(
            blockedHosts = blockedHosts + override.blockedHosts,
            suspiciousHosts = suspiciousHosts + override.suspiciousHosts,
            suspiciousUrlTokens = suspiciousUrlTokens + override.suspiciousUrlTokens,
            requestRules = requestRules + override.requestRules,
            domRules = domRules.mergedWith(override.domRules),
            blockOffsiteMainFrameNavigations =
                blockOffsiteMainFrameNavigations || override.blockOffsiteMainFrameNavigations,
            promptForOffsiteMainFrameNavigations =
                promptForOffsiteMainFrameNavigations && override.promptForOffsiteMainFrameNavigations,
        )
}

data class RequestRule(
    val id: String,
    val host: HostPattern? = null,
    val path: PathPattern? = null,
    val queryTokens: List<String> = emptyList(),
    val urlTokens: List<String> = emptyList(),
    val firstPartyOnly: Boolean = false,
    val appliesToMainFrame: Boolean = true,
    val appliesToSubresources: Boolean = true,
) {
    fun matches(url: String, profile: SiteProfile, isMainFrame: Boolean): Boolean {
        if (isMainFrame && !appliesToMainFrame) return false
        if (!isMainFrame && !appliesToSubresources) return false

        val parsed = url.toUriOrNull() ?: return false
        val normalizedPath = parsed.path.orEmpty().ifBlank { "/" }
        val normalizedQuery = parsed.rawQuery.orEmpty().lowercase(Locale.US)
        val lowerUrl = url.lowercase(Locale.US)

        return (host == null || host.matches(parsed.host)) &&
            (path == null || path.matches(normalizedPath)) &&
            queryTokens.all { normalizedQuery.contains(it.lowercase(Locale.US)) } &&
            urlTokens.all { lowerUrl.contains(it.lowercase(Locale.US)) } &&
            (!firstPartyOnly || profile.allowedHosts.any { it.matches(parsed.host) })
    }
}

data class DomCleanupRules(
    val suspiciousSelectors: List<String> = emptyList(),
    val preserveSelectors: List<String> = emptyList(),
    val ancestorCleanupRules: List<AncestorDomCleanupRule> = emptyList(),
    val suspiciousClassTokens: List<String> = emptyList(),
    val suspiciousUrlTokens: List<String> = emptyList(),
    val baitTextTokens: List<String> = emptyList(),
    val junkTextTokens: List<String> = emptyList(),
    val highZIndexThreshold: Int = 999,
    val overlayViewportCoverageThreshold: Double = 0.28,
    val enableGenericOverlayHeuristics: Boolean = true,
) {
    fun mergedWith(override: DomCleanupRules): DomCleanupRules =
        DomCleanupRules(
            suspiciousSelectors = suspiciousSelectors + override.suspiciousSelectors,
            preserveSelectors = preserveSelectors + override.preserveSelectors,
            ancestorCleanupRules = ancestorCleanupRules + override.ancestorCleanupRules,
            suspiciousClassTokens = suspiciousClassTokens + override.suspiciousClassTokens,
            suspiciousUrlTokens = suspiciousUrlTokens + override.suspiciousUrlTokens,
            baitTextTokens = baitTextTokens + override.baitTextTokens,
            junkTextTokens = junkTextTokens + override.junkTextTokens,
            highZIndexThreshold = minOf(highZIndexThreshold, override.highZIndexThreshold),
            overlayViewportCoverageThreshold =
                minOf(overlayViewportCoverageThreshold, override.overlayViewportCoverageThreshold),
            enableGenericOverlayHeuristics =
                enableGenericOverlayHeuristics && override.enableGenericOverlayHeuristics,
        )
}

data class AncestorDomCleanupRule(
    val markerSelector: String,
    val markerTextPrefixes: List<String>,
    val ancestorSelector: String,
    val ancestorParentSelector: String,
    val maxAncestorDepth: Int,
    val removalReason: String,
) {
    init {
        require(markerSelector.isNotBlank())
        require(markerTextPrefixes.isNotEmpty() && markerTextPrefixes.none { it.isBlank() })
        require(ancestorSelector.isNotBlank())
        require(ancestorParentSelector.isNotBlank())
        require(maxAncestorDepth in 0..12)
        require(removalReason.isNotBlank())
    }

    internal fun matchesMarkerText(text: String): Boolean {
        val normalized = text.trim()
        return markerTextPrefixes.any(normalized::startsWith)
    }
}

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

    data class LabelToken(private val token: String) : HostPattern() {
        private val normalized = token.normalizedHostLabel()

        override fun matches(host: String?): Boolean {
            val token = normalized ?: return false
            return host.normalizedHost()
                ?.split('.')
                ?.any { label ->
                    label == token ||
                        label.startsWith("$token-") ||
                        label.endsWith("-$token") ||
                        label.contains("-$token-")
                } == true
        }
    }
}

sealed class PathPattern {
    abstract fun matches(path: String?): Boolean

    data class Exact(private val path: String) : PathPattern() {
        private val normalized = path.normalizedPath() ?: "/"

        override fun matches(path: String?): Boolean = path.normalizedPath() == normalized
    }

    data class Prefix(private val prefix: String) : PathPattern() {
        private val normalized = prefix.normalizedPath() ?: "/"

        override fun matches(path: String?): Boolean =
            path.normalizedPath()?.startsWith(normalized) == true
    }

    data class Contains(private val token: String) : PathPattern() {
        private val normalized = token.lowercase(Locale.US)

        override fun matches(path: String?): Boolean =
            path.normalizedPath()?.contains(normalized) == true
    }

    data class RegularExpression(private val pattern: Regex) : PathPattern() {
        override fun matches(path: String?): Boolean =
            path.normalizedPath()?.matches(pattern) == true
    }
}

fun String?.normalizedHost(): String? =
    this
        ?.lowercase(Locale.US)
        ?.trim()
        ?.trimEnd('.')
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }

private fun String?.normalizedHostLabel(): String? =
    this
        ?.lowercase(Locale.US)
        ?.trim()
        ?.trim('.')
        ?.takeIf { it.isNotBlank() && '.' !in it }

fun String.hostFromUrl(): String? =
    toUriOrNull()
        ?.host
        .normalizedHost()

fun String.toUriOrNull(): URI? =
    runCatching { URI(this) }
        .getOrNull()

fun String?.normalizedPath(): String? =
    this
        ?.lowercase(Locale.US)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("/")) it else "/$it" }

fun suspiciousKeyRegex(): Regex = Regex(
    pattern = "(^|[-_.])(ad|ads|popup|redirect|interstitial|promo|campaign)([-_.]|$)|" +
        "(adid|ad_id|adsid|popup|redirect|interstitial|promo|campaign)",
    option = RegexOption.IGNORE_CASE,
)

fun protectedAccountKeyRegex(): Regex = Regex(
    pattern = "^(__Secure-|__Host-)?(SID|HSID|SSID|APISID|SAPISID|LOGIN_INFO|VISITOR_INFO1_LIVE|PREF|CONSENT)$|" +
        "^(google|youtube|yt)[-_.]",
    option = RegexOption.IGNORE_CASE,
)
