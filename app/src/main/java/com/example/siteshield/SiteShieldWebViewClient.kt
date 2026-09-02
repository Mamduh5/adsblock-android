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
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import java.io.ByteArrayInputStream

class SiteShieldWebViewClient(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val blockerEngine: GenericBlockerEngine,
    private val dataSaverModeStore: DataSaverModeStore,
    private val adaptiveShieldController: AdaptiveShieldController,
    initialProfile: SiteProfile,
    initialUrl: String? = null,
    private val onProfileMatched: (SiteProfile) -> Unit,
    private val onEvent: (DebugEvent) -> Unit,
    private val onAdaptivePageReady: (WebView) -> Unit,
    private val onStaticCleanupRequested: (WebView) -> Unit,
    private val onAdaptiveResourceActivity: () -> Unit,
    private val onBlockedResource: (BlockedResourceEvidence) -> Unit,
    private val onPageUsageCheckpoint: () -> Unit,
    private val onTopLevelNavigationStarted: (SiteProfile, String?) -> Unit,
    private val onRendererGone: () -> Unit = {},
    private val onPageReady: (WebView) -> Unit = {},
) : WebViewClient() {
    private val navigationIntentTracker = NavigationIntentTracker()
    private val profileHistory = TopLevelProfileHistory()
    private val topLevelContext = TopLevelContextStore(
        TopLevelContext(
            url = initialUrl,
            profile = initialProfile,
        ),
    )

    init {
        profileHistory.remember(initialUrl, initialProfile)
    }

    /**
     * Replaces the authoritative top-level context before an app-owned loadUrl call.
     * There is no reusable intent flag: the destination profile is resolved and installed atomically.
     */
    fun prepareExplicitNavigation(url: String): SiteProfile {
        navigationIntentTracker.prepareAppOwned(url)
        val profile = blockerEngine.profileForExplicitNavigation(url)
        replaceTopLevelContext(url, profile)
        return profile
    }

    /** Restores the profile recorded for exactly the WebView history entry being traversed. */
    fun prepareHistoryNavigation(url: String): SiteProfile {
        navigationIntentTracker.prepareAppOwned(url)
        val profile = profileHistory.profileFor(url)
            ?: blockerEngine.profileForExplicitNavigation(url)
        replaceTopLevelContext(url, profile)
        return profile
    }

    /** Captures popup ownership before the transient new-window WebView resolves its destination. */
    fun topLevelContextSnapshot(): TopLevelContext = topLevelContext.snapshot()

    fun navigationIntentGeneration(): Long = navigationIntentTracker.generation()
    fun navigationIntentChannelToken(): String = navigationIntentTracker.channelToken()

    fun observeNavigationIntentMessage(message: String): Boolean {
        val parsed = NavigationIntentMessage.parse(message) ?: return false
        return navigationIntentTracker.record(
            parsed.generation, parsed.token, parsed.host, parsed.path, parsed.targetBlank,
        )
    }

    /**
     * Applies the source page's policy before a popup target can be treated as explicit browsing.
     * Returns true only when the caller may load the target in the main WebView.
     */
    fun allowPopupNavigation(
        sourceContext: TopLevelContext,
        targetUrl: String,
        hasUserGesture: Boolean,
    ): Boolean {
        val intent = navigationIntentTracker.resolve(targetUrl, hasUserGesture, popup = true)
        val target = targetUrl.toUriOrNull()
        val sourcePageType = blockerEngine.classifyPageType(sourceContext.profile, sourceContext.url)
        val decision = blockerEngine.popupNavigationDecision(
            sourceProfile = sourceContext.profile,
            targetUrl = targetUrl,
            sourcePageUrl = sourceContext.url,
            hasUserGesture = intent.trusted,
            blockerEnabled = settingsStore.blockerEnabled,
        )
        val diagnostic = "sourceProfile=${sourceContext.profile.id}, sourcePageType=$sourcePageType, " +
            "targetScheme=${target?.scheme ?: "invalid"}, targetHost=${target?.host ?: "invalid"}, " +
            "hasGesture=$hasUserGesture, intent=${intent.category}, intended=${intent.trusted}, " +
            "onCreateWindow=true, actionView=false"

        return when (decision) {
            is BlockDecision.Block -> {
                if (!intent.trusted) {
                    adaptiveShieldController.observeNavigation(
                        profile = sourceContext.profile,
                        sourceUrl = sourceContext.url,
                        targetUrl = targetUrl,
                        popup = true,
                        blockedBySourcePolicy = decision.reason == BlockReason.OFFSITE_MAIN_FRAME,
                        intentMismatch = intent.category == NavigationIntentCategory.CLICK_HIJACK_SUSPECTED,
                        documentKey = navigationIntentChannelToken(),
                    )
                }
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
                if (!intent.trusted) {
                    adaptiveShieldController.observeNavigation(
                        profile = sourceContext.profile,
                        sourceUrl = sourceContext.url,
                        targetUrl = targetUrl,
                        popup = true,
                        blockedBySourcePolicy = false,
                        intentMismatch = intent.category == NavigationIntentCategory.CLICK_HIJACK_SUSPECTED,
                        documentKey = navigationIntentChannelToken(),
                    )
                }
                val adaptiveDecision = adaptiveShieldController.decideNavigation(
                    profile = sourceContext.profile,
                    sourceUrl = sourceContext.url,
                    targetUrl = targetUrl,
                    userInitiated = intent.trusted,
                    blockerEnabled = settingsStore.blockerEnabled,
                )
                if (adaptiveDecision is AdaptiveDecision.Block) {
                    onEvent(
                        DebugEvent(
                            category = DebugEventCategory.ADAPTIVE_BLOCK,
                            message = "[${sourceContext.profile.displayName}] Adaptive rule blocked new-window navigation",
                            detail = "$diagnostic, ruleId=${adaptiveDecision.ruleId}, " +
                                "confidence=${adaptiveDecision.confidence}, type=${adaptiveDecision.type}",
                        ),
                    )
                    return false
                }
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

        val currentPageUrl = activeContext.url
        val adaptiveProfile = activeContext.profile
        val decision = blockerEngine.navigationDecision(profile, url, currentPageUrl, request.isForMainFrame)
        val intent = if (request.isForMainFrame) {
            navigationIntentTracker.resolve(url, request.hasGesture(), popup = false)
        } else {
            NavigationIntentResult(NavigationIntentCategory.PAGE_DRIVEN, false)
        }
        val userInitiated = intent.trusted
        val intentMismatch = intent.category == NavigationIntentCategory.CLICK_HIJACK_SUSPECTED
        if (!settingsStore.blockerEnabled) {
            if (request.isForMainFrame && !userInitiated) {
                adaptiveShieldController.observeNavigation(
                    profile = adaptiveProfile,
                    sourceUrl = currentPageUrl,
                    targetUrl = url,
                    popup = false,
                    blockedBySourcePolicy = false,
                    intentMismatch = intentMismatch,
                    documentKey = navigationIntentChannelToken(),
                )
            }
            return false
        }

        when (decision) {
            is BlockDecision.Block -> {
                if (request.isForMainFrame && !userInitiated) {
                    adaptiveShieldController.observeNavigation(
                        profile = adaptiveProfile,
                        sourceUrl = currentPageUrl,
                        targetUrl = url,
                        popup = false,
                        blockedBySourcePolicy = decision.reason == BlockReason.OFFSITE_MAIN_FRAME,
                        intentMismatch = intentMismatch,
                        documentKey = navigationIntentChannelToken(),
                    )
                }
                val pageType = blockerEngine.classifyPageType(profile, currentPageUrl)
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.NAV_BLOCK,
                        message = "[${profile.displayName}] Blocked main-frame=${request.isForMainFrame} navigation to ${uri.host ?: uri}",
                        detail = "pageType=$pageType, reason=${decision.reason}, " +
                            "ruleId=${decision.ruleId ?: "none"}, targetScheme=${uri.scheme}, targetHost=${uri.host}",
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
            BlockDecision.Allow -> Unit
        }

        if (request.isForMainFrame && !userInitiated) {
            adaptiveShieldController.observeNavigation(
                profile = adaptiveProfile,
                sourceUrl = currentPageUrl,
                targetUrl = url,
                popup = false,
                blockedBySourcePolicy = false,
                intentMismatch = intentMismatch,
                documentKey = navigationIntentChannelToken(),
            )
            val adaptiveDecision = adaptiveShieldController.decideNavigation(
                profile = adaptiveProfile,
                sourceUrl = currentPageUrl,
                targetUrl = url,
                userInitiated = false,
                blockerEnabled = true,
            )
            if (adaptiveDecision is AdaptiveDecision.Block) {
                onEvent(
                    DebugEvent(
                        category = DebugEventCategory.ADAPTIVE_BLOCK,
                        message = "[${profile.displayName}] Adaptive rule blocked page-driven navigation",
                        detail = "ruleId=${adaptiveDecision.ruleId}, confidence=${adaptiveDecision.confidence}, " +
                            "type=${adaptiveDecision.type}, intent=${intent.category}, " +
                            "targetScheme=${uri.scheme}, targetHost=${uri.host}",
                    ),
                )
                return true
            }
        }
        return false
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null

        val uri = request.url
        val requestUrl = uri.toString()
        val activeContext = topLevelContext.snapshot()
        val profile = blockerEngine.profileForRequest(
            requestUrl,
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

        val resourceKind = adaptiveResourceKind(requestUrl, request.requestHeaders.orEmpty())
        val blockerEnabled = settingsStore.blockerEnabled
        val decision = if (blockerEnabled) {
            blockerEngine.resourceDecision(profile, requestUrl, activeContext.url)
        } else {
            BlockDecision.Allow
        }
        if (AdaptiveRuntimeModePolicy.observes(adaptiveShieldController.mode())) {
            adaptiveShieldController.observeRequest(
                profile = profile,
                pageUrl = activeContext.url,
                requestUrl = requestUrl,
                blockedByStaticRule = decision is BlockDecision.Block,
                resourceKind = resourceKind,
                documentKey = navigationIntentChannelToken(),
            )
            onAdaptiveResourceActivity()
        }
        if (decision is BlockDecision.Block) {
            val pageType = blockerEngine.classifyPageType(profile, activeContext.url)
            onEvent(
                DebugEvent(
                    category = DebugEventCategory.RESOURCE_BLOCK,
                    message = "[${profile.displayName}] Blocked subresource from ${uri.host ?: uri}",
                    detail = "pageType=$pageType, reason=${decision.reason}, " +
                        "ruleId=${decision.ruleId ?: "none"}, targetScheme=${uri.scheme}, targetHost=${uri.host}",
                ),
            )
            BlockedResourceEvidence.from(activeContext.url, requestUrl, resourceKind, System.currentTimeMillis())
                ?.let(onBlockedResource)
            return emptyResponse()
        }
        val adaptiveDecision = adaptiveShieldController.decideRequest(
            profile = profile,
            pageUrl = activeContext.url,
            requestUrl = requestUrl,
            resourceKind = resourceKind,
            blockerEnabled = blockerEnabled,
            userInitiated = request.hasGesture(),
        )
        if (adaptiveDecision !is AdaptiveDecision.Block) return null

        val pageType = blockerEngine.classifyPageType(profile, activeContext.url)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.ADAPTIVE_BLOCK,
                message = "[${profile.displayName}] Adaptive rule blocked ${uri.host ?: "unknown host"}",
                detail = "pageType=$pageType, reason=ADAPTIVE_RULE, ruleId=${adaptiveDecision.ruleId}, " +
                    "confidence=${adaptiveDecision.confidence}, type=${adaptiveDecision.type}, " +
                    "targetScheme=${uri.scheme}, targetHost=${uri.host}",
            ),
        )
        BlockedResourceEvidence.from(activeContext.url, requestUrl, resourceKind, System.currentTimeMillis())
            ?.let(onBlockedResource)
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
        navigationIntentTracker.documentStarted()
        val updatedContext = updateTopLevelContext(url)
        val profile = updatedContext.profile
        val pageType = blockerEngine.classifyPageType(profile, updatedContext.url)
        onTopLevelNavigationStarted(profile, updatedContext.url)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.PAGE_TYPE,
                message = "[${profile.displayName}] Loading page",
                detail = "detected=$pageType, ${safeUrlDiagnostic(url)}",
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
        if (AdaptiveRuntimeModePolicy.observes(adaptiveShieldController.mode())) onAdaptivePageReady(view)
        if (AdaptiveRuntimeModePolicy.performsStaticCleanup(settingsStore.blockerEnabled)) {
            onStaticCleanupRequested(view)
        }
        onPageReady(view)
        onPageUsageCheckpoint()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onEvent(
            DebugEvent(
                category = DebugEventCategory.POLICY_DECISION,
                message = "WebView renderer ended",
                detail = "didCrash=${detail.didCrash()}, rendererPriority=${detail.rendererPriorityAtExit()}",
            ),
        )
        onRendererGone()
        return true
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val previousContext = topLevelContext.snapshot()
        if (!isNewTopLevelHistoryUrl(previousContext.url, url)) return

        navigationIntentTracker.documentStarted()
        val updatedContext = updateTopLevelContext(url)
        val pageType = blockerEngine.classifyPageType(updatedContext.profile, updatedContext.url)
        onTopLevelNavigationStarted(updatedContext.profile, updatedContext.url)
        onEvent(
            DebugEvent(
                category = DebugEventCategory.PAGE_TYPE,
                message = "[${updatedContext.profile.displayName}] History updated",
                detail = "detected=$pageType, reload=$isReload, ${safeUrlDiagnostic(url)}",
            ),
        )
        onEvent(
            DebugEvent(
                category = DebugEventCategory.POLICY_DECISION,
                message = "Active merged policy",
                detail = blockerEngine.describePolicy(updatedContext.profile, pageType),
            ),
        )
        if (AdaptiveRuntimeModePolicy.observes(adaptiveShieldController.mode())) onAdaptivePageReady(view)
        if (AdaptiveRuntimeModePolicy.performsStaticCleanup(settingsStore.blockerEnabled)) {
            onStaticCleanupRequested(view)
        }
        onPageReady(view)
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
