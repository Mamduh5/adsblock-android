package com.example.siteshield

import android.webkit.CookieManager
import android.webkit.WebView

class SiteDataCleaner(
    private val webView: WebView,
    private val blockerEngine: GenericBlockerEngine,
    private val currentProfile: () -> SiteProfile,
    private val onEvent: (DebugEvent) -> Unit,
) {
    fun cleanSuspiciousSiteData() {
        val profile = currentProfile()
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = cookieManager.getCookie(profile.startUrl).orEmpty()
        var removedCookies = 0

        cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() }
            .mapNotNull { cookieName ->
                blockerEngine.matchingSuspiciousCookiePattern(profile, cookieName)
                    ?.let { pattern -> cookieName to pattern }
            }
            .forEach { cookieName ->
                expireCookie(cookieManager, profile, cookieName.first)
                removedCookies += 1
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.COOKIE_CLEANUP,
                        message = "[${profile.displayName}] Removed suspicious cookie: ${cookieName.first}",
                        detail = "matchedPattern=${cookieName.second.pattern}",
                    ),
                )
            }

        cookieManager.flush()

        val storagePatterns = profile.suspiciousStorageKeyPatterns.joinToString("|") {
            it.pattern
        }.ifBlank { "(?!)" }
        val protectedStoragePatterns = profile.protectedStorageKeyPatterns.joinToString("|") {
            it.pattern
        }.ifBlank { "(?!)" }
        val cleanupScript = """
            (function() {
              const pattern = new RegExp(${storagePatterns.toJavascriptString()}, 'i');
              const protectedPattern = new RegExp(${protectedStoragePatterns.toJavascriptString()}, 'i');
              const stores = [window.localStorage, window.sessionStorage].filter(Boolean);
              const removed = [];
              for (const store of stores) {
                const keys = [];
                for (let i = 0; i < store.length; i++) {
                  keys.push(store.key(i));
                }
                keys.filter(Boolean).forEach(function(key) {
                  if (pattern.test(key) && !protectedPattern.test(key)) {
                    store.removeItem(key);
                    removed.push(key);
                  }
                });
              }
              return removed.join('\n');
            })();
        """.trimIndent()

        webView.evaluateJavascript(cleanupScript) { result ->
            val storageKeys = decodeJavascriptString(result)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()

            storageKeys.forEach {
                val matchedPattern = blockerEngine.matchingSuspiciousStoragePattern(profile, it)
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.STORAGE_CLEANUP,
                        message = "[${profile.displayName}] Removed suspicious storage key: $it",
                        detail = "matchedPattern=${matchedPattern?.pattern ?: "js-pattern"}",
                    ),
                )
            }

            if (removedCookies == 0 && storageKeys.isEmpty()) {
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.STORAGE_CLEANUP,
                        message = "[${profile.displayName}] Suspicious site data cleanup ran",
                        detail = "removedCookies=0, removedStorageKeys=0",
                    ),
                )
            }
        }
    }

    private fun expireCookie(cookieManager: CookieManager, profile: SiteProfile, cookieName: String) {
        val targetHost = profile.startUrl.hostFromUrl() ?: return
        val expirationValues = listOf(
            "$cookieName=; Max-Age=0; Path=/",
            "$cookieName=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/",
            "$cookieName=; Max-Age=0; Path=/; Domain=$targetHost",
            "$cookieName=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$targetHost",
            "$cookieName=; Max-Age=0; Path=/; Domain=.$targetHost",
            "$cookieName=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.$targetHost",
        )

        listOf(targetHost, "www.$targetHost").distinct().forEach { host ->
            expirationValues.forEach { cookieManager.setCookie("https://$host", it) }
        }
    }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return raw
            .removeSurrounding("\"")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun String.toJavascriptString(): String =
        buildString {
            append('"')
            this@toJavascriptString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
            append('"')
        }
}
