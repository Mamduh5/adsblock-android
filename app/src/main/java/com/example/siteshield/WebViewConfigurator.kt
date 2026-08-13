package com.example.siteshield

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewConfigurator {
    fun configure(webView: WebView, profile: SiteProfile, dataSaverMode: DataSaverMode) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, profile.allowThirdPartyCookies)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = profile.dataSaverPolicy.blockNetworkImages(dataSaverMode, PageType.UNKNOWN)
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

    fun applyDataSaverPolicy(
        webView: WebView,
        mode: DataSaverMode,
        profile: SiteProfile,
        pageType: PageType,
    ) {
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = profile.dataSaverPolicy.blockNetworkImages(mode, pageType)
        }
    }
}
