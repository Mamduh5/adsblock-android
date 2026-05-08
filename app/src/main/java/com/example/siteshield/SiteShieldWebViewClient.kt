package com.example.siteshield

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayInputStream

class SiteShieldWebViewClient(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val blockerEngine: GenericBlockerEngine,
    private val currentProfile: () -> SiteProfile,
    private val onProfileMatched: (SiteProfile) -> Unit,
    private val onEvent: (DebugEvent) -> Unit,
    private val onPageLoaded: (WebView) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val url = uri.toString()
        val profile = blockerEngine.profileForUrl(url, currentProfile())
        onProfileMatched(profile)

        if (!settingsStore.blockerEnabled) return false

        val currentPageUrl = view.url
        if (blockerEngine.isSuspiciousNavigation(profile, url, currentPageUrl, request.isForMainFrame)) {
            val pageType = blockerEngine.classifyPageType(profile, currentPageUrl)
            val matchedRule = blockerEngine.matchingRequestRule(profile, url, currentPageUrl, request.isForMainFrame)
            onEvent(
                DebugEvent(
                    category = DebugEventCategory.NAV_BLOCK,
                    message = "[${profile.displayName}] Blocked main-frame=${request.isForMainFrame} navigation to ${uri.host ?: uri}",
                    detail = "pageType=$pageType, matchedRule=${matchedRule?.id ?: "policy"}, url=$url",
                ),
            )
            if (profile.warnOnSuspiciousNavigation) {
                Toast.makeText(context, "Blocked suspicious navigation", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        if (blockerEngine.isAllowedHost(profile, uri.host)) {
            return false
        }

        if (request.isForMainFrame && blockerEngine.shouldPromptForOffsiteMainFrameNavigation(profile, url, currentPageUrl)) {
            promptExternalOpen(uri)
            return true
        }

        return false
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!settingsStore.blockerEnabled || request.isForMainFrame) return null

        val uri = request.url
        val profile = blockerEngine.profileForUrl(uri.toString(), currentProfile())
        if (!blockerEngine.isBlockedResource(profile, uri.toString(), view.url)) return null

        val pageType = blockerEngine.classifyPageType(profile, view.url)
        val matchedRule = blockerEngine.matchingRequestRule(profile, uri.toString(), view.url, isMainFrame = false)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.RESOURCE_BLOCK,
                message = "[${profile.displayName}] Blocked subresource from ${uri.host ?: uri}",
                detail = "pageType=$pageType, matchedRule=${matchedRule?.id ?: "policy"}, url=${uri}",
            ),
        )
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val profile = blockerEngine.profileForUrl(url, currentProfile())
        onProfileMatched(profile)
        val pageType = blockerEngine.classifyPageType(profile, url)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.PAGE_TYPE,
                message = "[${profile.displayName}] Loading ${url.orEmpty()}",
                detail = "detected=$pageType",
            ),
        )
        onEvent(
            DebugEvent(
                category = DebugEventCategory.POLICY_DECISION,
                message = "Active merged policy",
                detail = blockerEngine.describePolicy(profile, pageType),
            ),
        )
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (settingsStore.blockerEnabled) {
            onPageLoaded(view)
        }
    }

    private fun promptExternalOpen(uri: Uri) {
        AlertDialog.Builder(context)
            .setTitle("Open external link?")
            .setMessage(uri.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }
}
