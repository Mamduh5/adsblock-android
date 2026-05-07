package com.example.siteshield

import android.webkit.CookieManager
import android.webkit.WebView

class SiteDataCleaner(
    private val webView: WebView,
    private val onEvent: (BlockedEvent) -> Unit,
) {
    fun cleanSuspiciousSiteData() {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = cookieManager.getCookie(BlockerConfig.TargetUrl).orEmpty()
        var removedCookies = 0

        cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotBlank() && BlockerConfig.isSuspiciousDataKey(it) }
            .forEach { cookieName ->
                expireCookie(cookieManager, cookieName)
                removedCookies += 1
                onEvent(BlockedEvent("cookie", "Removed suspicious cookie: $cookieName"))
            }

        cookieManager.flush()

        val cleanupScript = """
            (function() {
              const pattern = /(^|[-_.])(ad|ads|popup|redirect|interstitial|promo|campaign)([-_.]|${'$'})|(adid|ad_id|adsid|popup|redirect|interstitial|promo|campaign)/i;
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
                onEvent(BlockedEvent("storage", "Removed suspicious storage key: $it"))
            }

            if (removedCookies == 0 && storageKeys.isEmpty()) {
                onEvent(BlockedEvent("clean", "No suspicious site data matched configured patterns"))
            }
        }
    }

    private fun expireCookie(cookieManager: CookieManager, cookieName: String) {
        val expirationValues = listOf(
            "$cookieName=; Max-Age=0; Path=/",
            "$cookieName=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/",
            "$cookieName=; Max-Age=0; Path=/; Domain=${BlockerConfig.TargetHost}",
            "$cookieName=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=${BlockerConfig.TargetHost}",
            "$cookieName=; Max-Age=0; Path=/; Domain=.${BlockerConfig.TargetHost}",
            "$cookieName=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.${BlockerConfig.TargetHost}",
        )

        listOf(BlockerConfig.TargetHost, "www.${BlockerConfig.TargetHost}").forEach { host ->
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
}
