package com.example.siteshield

import android.webkit.CookieManager
import android.webkit.WebView

class SiteDataCleaner(
    private val webView: WebView,
    private val blockerEngine: GenericBlockerEngine,
    private val currentProfile: () -> SiteProfile,
    private val onEvent: (BlockedEvent) -> Unit,
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
            .filter { it.isNotBlank() && blockerEngine.isSuspiciousCookieKey(profile, it) }
            .forEach { cookieName ->
                expireCookie(cookieManager, profile, cookieName)
                removedCookies += 1
                onEvent(BlockedEvent("cookie", "[${profile.displayName}] Removed suspicious cookie: $cookieName"))
            }

        cookieManager.flush()

        val storagePatterns = profile.suspiciousStorageKeyPatterns.joinToString("|") {
            it.pattern
        }.ifBlank { "(?!)" }
        val cleanupScript = """
            (function() {
              const pattern = new RegExp(${storagePatterns.toJavascriptString()}, 'i');
              const stores = [window.localStorage, window.sessionStorage].filter(Boolean);
              const removed = [];
              for (const store of stores) {
                const keys = [];
                for (let i = 0; i < store.length; i++) {
                  keys.push(store.key(i));
                }
                keys.filter(Boolean).forEach(function(key) {
                  if (pattern.test(key)) {
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
                onEvent(BlockedEvent("storage", "[${profile.displayName}] Removed suspicious storage key: $it"))
            }

            if (removedCookies == 0 && storageKeys.isEmpty()) {
                onEvent(BlockedEvent("clean", "[${profile.displayName}] No suspicious site data matched configured patterns"))
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
