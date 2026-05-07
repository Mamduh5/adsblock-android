package com.example.siteshield

import android.net.Uri
import java.util.Locale

object BlockerConfig {
    const val AppName = "Site Shield Mobile"
    const val TargetUrl = "https://example.com/"
    const val TargetHost = "example.com"
    const val WarnOnSuspiciousNavigation = true

    private val targetHostSuffixes = setOf(
        "example.com",
    )

    private val blockedHostSuffixes = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "propellerads.com",
        "onclickads.net",
        "adsterra.com",
        "trafficjunky.net",
        "mgid.com",
        "exosrv.com",
        "redirectingat.com",
    )

    private val suspiciousHostTokens = listOf(
        "adserver",
        "ads.",
        "popunder",
        "popup",
        "redirect",
        "interstitial",
        "clicktrack",
        "push-notif",
    )

    private val suspiciousPathTokens = listOf(
        "/ads/",
        "/ad/",
        "/popunder",
        "/popup",
        "/redirect",
        "/interstitial",
        "/clicktrap",
        "/fake-close",
        "/tracking",
        "/track/",
        "/campaign",
        "/promo",
    )

    private val suspiciousKeyRegex = Regex(
        pattern = "(^|[-_.])(ad|ads|popup|redirect|interstitial|promo|campaign)([-_.]|$)|" +
            "(adid|ad_id|adsid|popup|redirect|interstitial|promo|campaign)",
        option = RegexOption.IGNORE_CASE,
    )

    fun isTargetHost(host: String?): Boolean {
        val normalized = host.normalizedHost() ?: return false
        return targetHostSuffixes.any { normalized == it || normalized.endsWith(".$it") }
    }

    fun isBlockedHost(host: String?): Boolean {
        val normalized = host.normalizedHost() ?: return false
        return blockedHostSuffixes.any { normalized == it || normalized.endsWith(".$it") } ||
            suspiciousHostTokens.any { normalized.contains(it) }
    }

    fun isSuspiciousNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return true

        val host = uri.host
        if (isBlockedHost(host)) return true

        val lowerUrl = uri.toString().lowercase(Locale.US)
        return suspiciousPathTokens.any { lowerUrl.contains(it) }
    }

    fun isBlockedResource(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return false

        if (isBlockedHost(uri.host)) return true

        val lowerUrl = uri.toString().lowercase(Locale.US)
        return suspiciousPathTokens.any { lowerUrl.contains(it) }
    }

    fun isSuspiciousDataKey(name: String): Boolean = suspiciousKeyRegex.containsMatchIn(name)

    private fun String?.normalizedHost(): String? =
        this?.lowercase(Locale.US)?.removePrefix("www.")?.trimEnd('.')
}
