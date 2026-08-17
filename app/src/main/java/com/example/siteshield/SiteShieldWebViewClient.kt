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
    private val dataSaverModeStore: DataSaverModeStore,
    initialProfile: SiteProfile,
    private val onProfileMatched: (SiteProfile) -> Unit,
    private val onEvent: (DebugEvent) -> Unit,
    private val onPageLoaded: (WebView) -> Unit,
    private val onPageUsageCheckpoint: () -> Unit,
    private val onTopLevelNavigationStarted: (SiteProfile, String?) -> Unit,
) : WebViewClient() {
    private val profileHistory = TopLevelProfileHistory()
    private val topLevelContext = TopLevelContextStore(
        TopLevelContext(
            url = null,
            profile = initialProfile,
        ),
    )

    /**
     * Replaces the authoritative top-level context before an app-owned loadUrl call.
     * There is no reusable intent flag: the destination profile is resolved and installed atomically.
     */
    fun prepareExplicitNavigation(url: String): SiteProfile {
        val profile = blockerEngine.profileForExplicitNavigation(url)
        replaceTopLevelContext(url, profile)
        return profile
    }

    /** Restores the profile recorded for exactly the WebView history entry being traversed. */
    fun prepareHistoryNavigation(url: String): SiteProfile {
        val profile = profileHistory.profileFor(url)
            ?: blockerEngine.profileForExplicitNavigation(url)
        replaceTopLevelContext(url, profile)
        return profile
    }

    /** Captures popup ownership before the transient new-window WebView resolves its destination. */
    fun topLevelContextSnapshot(): TopLevelContext = topLevelContext.snapshot()

    /**
     * Applies the source page's policy before a popup target can be treated as explicit browsing.
     * Returns true only when the caller may load the target in the main WebView.
     */
    fun allowPopupNavigation(
        sourceContext: TopLevelContext,
        targetUrl: String,
        hasUserGesture: Boolean,
    ): Boolean {
        val target = targetUrl.toUriOrNull()
        val sourcePageType = blockerEngine.classifyPageType(sourceContext.profile, sourceContext.url)
        val decision = blockerEngine.popupNavigationDecision(
            sourceProfile = sourceContext.profile,
            targetUrl = targetUrl,
            sourcePageUrl = sourceContext.url,
            hasUserGesture = hasUserGesture,
            blockerEnabled = settingsStore.blockerEnabled,
        )
        val diagnostic = "sourceProfile=${sourceContext.profile.id}, sourcePageType=$sourcePageType, " +
            "targetScheme=${target?.scheme ?: "invalid"}, targetHost=${target?.host ?: "invalid"}, " +
            "hasGesture=$hasUserGesture, onCreateWindow=true, actionView=false"

        return when (decision) {
            is BlockDecision.Block -> {
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.POPUP_BLOCK,
                        message = "[${sourceContext.profile.displayName}] Blocked new-window navigation",
                        detail = "$diagnostic, decision=Block, reason=${decision.reason}, " +
                            "ruleId=${decision.ruleId ?: "none"}, loadUrl=false",
                    ),
                )
                false
            }
            is BlockDecision.PromptExternal -> {
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.POLICY_DECISION,
                        message = "[${sourceContext.profile.displayName}] Prompted for new-window navigation",
                        detail = "$diagnostic, decision=PromptExternal, loadUrl=false",
                    ),
                )
                promptExternalOpen(Uri.parse(targetUrl))
                false
            }
            BlockDecision.Allow -> {
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.POLICY_DECISION,
                        message = "[${sourceContext.profile.displayName}] Allowed new-window navigation",
                        detail = "$diagnostic, decision=Allow, loadUrl=true",
                    ),
                )
                true
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val url = uri.toString()
        val activeContext = topLevelContext.snapshot()
        val profile = blockerEngine.profileForNavigationPolicy(
            url = url,
            activeTopLevelProfile = activeContext.profile,
            isMainFrame = request.isForMainFrame,
        )

        if (!settingsStore.blockerEnabled) return false

        val currentPageUrl = activeContext.url
        when (val decision = blockerEngine.navigationDecision(profile, url, currentPageUrl, request.isForMainFrame)) {
            is BlockDecision.Block -> {
                val pageType = blockerEngine.classifyPageType(profile, currentPageUrl)
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.NAV_BLOCK,
                        message = "[${profile.displayName}] Blocked main-frame=${request.isForMainFrame} navigation to ${uri.host ?: uri}",
                        detail = "pageType=$pageType, reason=${decision.reason}, ruleId=${decision.ruleId ?: "none"}, url=$url",
                    ),
                )
                if (profile.warnOnSuspiciousNavigation) {
                    Toast.makeText(context, "Blocked suspicious navigation", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            is BlockDecision.PromptExternal -> {
                promptExternalOpen(uri)
                return true
            }
            BlockDecision.Allow -> return false
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null

        val uri = request.url
        val activeContext = topLevelContext.snapshot()
        val profile = blockerEngine.profileForRequest(
            uri.toString(),
            activeContext.profile,
            ProfileRequestContext.SUBRESOURCE,
        )
        val saverDecision = DataSaverEngine.decide(
            mode = dataSaverModeStore.snapshot(),
            policy = profile.dataSaverPolicy,
            request = DataSaverRequestContext(
                method = request.method,
                isForMainFrame = request.isForMainFrame,
                hasGesture = request.hasGesture(),
                headers = request.requestHeaders.orEmpty(),
            ),
        )
        if (saverDecision is DataSaverDecision.Block) {
            onEvent(
                DebugEvent(
                    category = DebugEventCategory.DATA_SAVER_BLOCK,
                    message = "[${profile.displayName}] Blocked explicit prefetch",
                    detail = "ruleId=${saverDecision.ruleId}",
                ),
            )
            return emptyResponse()
        }

        if (!settingsStore.blockerEnabled) return null
        val decision = blockerEngine.resourceDecision(profile, uri.toString(), activeContext.url)
        if (decision !is BlockDecision.Block) return null

        val pageType = blockerEngine.classifyPageType(profile, activeContext.url)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.RESOURCE_BLOCK,
                message = "[${profile.displayName}] Blocked subresource from ${uri.host ?: uri}",
                detail = "pageType=$pageType, reason=${decision.reason}, ruleId=${decision.ruleId ?: "none"}, url=${uri}",
            ),
        )
        return emptyResponse()
    }

    private fun emptyResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val updatedContext = updateTopLevelContext(url)
        val profile = updatedContext.profile
        val pageType = blockerEngine.classifyPageType(profile, updatedContext.url)
        onTopLevelNavigationStarted(profile, updatedContext.url)
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
        onPageUsageCheckpoint()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val previousContext = topLevelContext.snapshot()
        if (!isNewTopLevelHistoryUrl(previousContext.url, url)) return

        val updatedContext = updateTopLevelContext(url)
        val pageType = blockerEngine.classifyPageType(updatedContext.profile, updatedContext.url)
        onTopLevelNavigationStarted(updatedContext.profile, updatedContext.url)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.PAGE_TYPE,
                message = "[${updatedContext.profile.displayName}] History updated ${url.orEmpty()}",
                detail = "detected=$pageType, reload=$isReload",
            ),
        )
        onEvent(
            DebugEvent(
                category = DebugEventCategory.POLICY_DECISION,
                message = "Active merged policy",
                detail = blockerEngine.describePolicy(updatedContext.profile, pageType),
            ),
        )
        if (settingsStore.blockerEnabled) {
            onPageLoaded(view)
        }
        onPageUsageCheckpoint()
    }

    private fun updateTopLevelContext(url: String?): TopLevelContext {
        val previousContext = topLevelContext.snapshot()
        val updatedUrl = url ?: previousContext.url
        val updatedProfile = blockerEngine.profileForTopLevelUrl(updatedUrl, previousContext.profile)
        return replaceTopLevelContext(updatedUrl, updatedProfile)
    }

    private fun replaceTopLevelContext(url: String?, profile: SiteProfile): TopLevelContext {
        val updatedContext = TopLevelContext(url, profile)
        topLevelContext.update(updatedContext)
        profileHistory.remember(url, profile)
        onProfileMatched(profile)
        return updatedContext
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
