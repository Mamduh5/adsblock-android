package com.example.siteshield

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface NavigationTarget {
    data class Url(val url: String) : NavigationTarget
    data class SearchQuery(val query: String) : NavigationTarget
    data class Invalid(val reason: String) : NavigationTarget
}

object OmniboxInputParser {
    private val schemePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val domainLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun parse(input: String): NavigationTarget? {
        val value = input.trim()
        if (value.isEmpty()) return null

        if (schemePrefix.containsMatchIn(value)) {
            val uri = runCatching { URI(value) }.getOrNull()
                ?: return NavigationTarget.Invalid("Malformed URL")
            if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) {
                return NavigationTarget.Invalid("Only HTTP and HTTPS URLs are supported")
            }
            if (!isValidHost(uri.host)) return NavigationTarget.Invalid("Malformed URL")
            return NavigationTarget.Url(uri.toASCIIString())
        }

        if (!value.any(Char::isWhitespace)) {
            val candidate = runCatching { URI("https://$value") }.getOrNull()
            if (candidate != null && isValidHost(candidate.host)) {
                return NavigationTarget.Url(candidate.toASCIIString())
            }
        }

        return NavigationTarget.SearchQuery(value)
    }

    private fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trimEnd('.')
        if (normalized.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))) {
            return normalized.split('.').all { it.toIntOrNull() in 0..255 }
        }
        val labels = normalized.split('.')
        if (labels.size < 2 || labels.any { !domainLabel.matches(it) }) return false
        val topLevel = labels.last()
        return topLevel.startsWith("xn--", ignoreCase = true) ||
            (topLevel.length >= 2 && topLevel.all(Char::isLetter))
    }
}

enum class SearchProvider(
    val displayName: String,
    private val searchUrlPrefix: String,
) {
    GOOGLE("Google", "https://www.google.com/search?q="),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/search?q="),
    ;

    fun buildSearchUrl(query: String): String =
        searchUrlPrefix + URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    fun next(): SearchProvider = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStoredValue(value: String?): SearchProvider =
            entries.firstOrNull { it.name == value } ?: GOOGLE
    }
}
