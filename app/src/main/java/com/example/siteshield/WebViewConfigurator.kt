package com.example.siteshield

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewConfigurator {
    fun configure(webView: WebView, profile: SiteProfile) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, profile.allowThirdPartyCookies)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString SiteShieldMobile/1.0"
        }

        webView.isLongClickable = true
        webView.setOnLongClickListener { false }
    }

    fun applyCookiePolicy(webView: WebView, profile: SiteProfile) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, profile.allowThirdPartyCookies)
    }
}
