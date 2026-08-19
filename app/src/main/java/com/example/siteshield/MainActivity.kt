package com.example.siteshield

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.os.Handler
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private data class TabRuntime(
        val tabId: String,
        val webView: WebView,
        val client: SiteShieldWebViewClient,
        val downloadIntentTracker: DownloadIntentTracker,
    )

    private val blockerEngine = GenericBlockerEngine()
    private lateinit var settingsStore: SettingsStore
    private lateinit var dataSaverModeStore: DataSaverModeStore
    private lateinit var dataUsageTracker: DataUsageTracker
    private lateinit var downloadCoordinator: DownloadCoordinator
    private lateinit var adaptiveShieldController: AdaptiveShieldController
    private lateinit var sessionRepository: BrowserSessionRepository
    private lateinit var tabManager: BrowserTabManager
    private val tabRuntimes = linkedMapOf<String, TabRuntime>()
    private val pendingScrollRestores = mutableMapOf<String, Int>()
    private val persistenceHandler = Handler(Looper.getMainLooper())
    private val persistSessionRunnable = Runnable { persistSessionNow() }
    private lateinit var activeProfile: SiteProfile
    private lateinit var webView: WebView
    private lateinit var webViewClient: SiteShieldWebViewClient
    private lateinit var browseHome: View
    private lateinit var contentLayer: FrameLayout
    private lateinit var domainText: TextView
    private lateinit var profileButton: Button
    private lateinit var logText: TextView
    private lateinit var debugPanel: ScrollView
    private lateinit var debugTools: LinearLayout
    private lateinit var markerText: TextView
    private lateinit var filterButton: Button
    private lateinit var shieldPanel: LinearLayout
    private lateinit var debugOverlay: FrameLayout
    private lateinit var shieldControl: Button
    private lateinit var dataSaverButton: Button
    private lateinit var adaptiveModeButton: Button
    private lateinit var dataUsageText: TextView
    private lateinit var searchProviderButton: Button
    private lateinit var tabsButton: Button
    private val eventLog = DebugEventLog(MAX_EVENTS)
    private var debugFilter: DebugEventCategory? = null
    private var shieldUiState = ShieldUiState()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        sessionRepository = SharedPreferencesBrowserSessionRepository(this)
        tabManager = BrowserTabManager(sessionRepository.load())
        activeProfile = SiteProfileRegistry.byId(tabManager.selectedTab().profileId)
        dataSaverModeStore = DataSaverModeStore(settingsStore.dataSaverMode)
        dataUsageTracker = DataUsageTracker(AndroidNetworkCounterProvider, activeProfile.id)
        downloadCoordinator = DownloadCoordinator(this, onEvent = ::recordEvent)
        adaptiveShieldController = AdaptiveShieldController(
            persistence = SharedPreferencesAdaptiveStatePersistence(this),
            initialMode = settingsStore.adaptiveShieldMode,
            profileById = SiteProfileRegistry::byId,
            onEvent = ::recordEvent,
        )

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        val initialRuntime = createTabRuntime(tabManager.selectedTab())
        activateRuntime(initialRuntime)
        val root = buildUi()
        setContentView(root)
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.PROFILE,
                message = "Active profile: ${activeProfile.displayName}",
                detail = "id=${activeProfile.id}",
            ),
        )
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.ADAPTIVE_OBSERVE,
                message = "Adaptive Shield mode=${adaptiveShieldController.mode().name}",
                detail = "localOnly=true, remoteLearning=false",
            ),
        )
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.DATA_SAVER,
                message = "Data Saver mode=${dataSaverModeStore.snapshot().name}",
            ),
        )

        val selected = tabManager.selectedTab()
        if (selected.currentUrl == null) {
            showBrowseHome()
        } else {
            webViewClient.prepareExplicitNavigation(selected.currentUrl)
            webView.loadUrl(selected.currentUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        checkpointActiveScroll()
        persistSessionNow()
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (shieldUiState.consumesBack()) {
            shieldUiState = shieldUiState.afterBack()
            syncShieldUi()
        } else if (webView.canGoBack()) {
            navigateHistory(-1)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        checkpointActiveScroll()
        scheduleSessionPersistence()
        super.onPause()
    }

    override fun onStop() {
        persistSessionNow()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val memoryPressure = level == TRIM_MEMORY_RUNNING_LOW ||
            level == TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= TRIM_MEMORY_BACKGROUND
        if (memoryPressure) {
            suspendExcessRuntimes(allBackground = true)
            persistSessionNow()
        }
    }

    override fun onDestroy() {
        dataUsageTracker.flush()
        persistenceHandler.removeCallbacks(persistSessionRunnable)
        checkpointActiveScroll()
        persistSessionNow()
        downloadCoordinator.close()
        adaptiveShieldController.close()
        tabRuntimes.values.toList().forEach(::destroyRuntime)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        contentLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(contentLayer)

        domainText = TextView(this).apply {
            text = "Site Shield - ${activeProfile.displayName}"
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 2
        }

        val backButton = smallButton("Back") {
            navigateHistory(-1)
        }

        val forwardButton = smallButton("Forward") {
            navigateHistory(1)
        }

        val reloadButton = smallButton("Reload") {
            webView.reload()
        }

        val browseButton = largeButton("Browse / Search") {
            showOmnibox()
        }

        val downloadsButton = largeButton("Downloads") {
            collapseShieldPanel()
            showDownloadsDialog()
        }

        tabsButton = largeButton("Tabs (${tabManager.allTabs().size})") {
            collapseShieldPanel()
            showTabsDialog()
        }

        val cleanupButton = smallButton("Cleanup") {
            injectDomCleanup(webView)
        }

        profileButton = smallButton("Profile: ${activeProfile.displayName}") {
            showProfilePicker()
        }

        val blockerSwitch = Switch(this).apply {
            text = "Blocker"
            textSize = 15f
            minimumHeight = dp(48)
            isChecked = settingsStore.blockerEnabled
            setOnCheckedChangeListener { _, enabled ->
                settingsStore.blockerEnabled = enabled
                recordEvent(
                    DebugEvent(
                        category = DebugEventCategory.POLICY_DECISION,
                        message = "Blocker ${if (enabled) "enabled" else "disabled"}",
                    ),
                )
                if (enabled) injectDomCleanup(webView)
            }
        }

        val dataCleanButton = smallButton("Data") {
            SiteDataCleaner(
                webView = webView,
                blockerEngine = blockerEngine,
                currentProfile = { activeProfile },
                onEvent = ::recordEvent,
            ).cleanSuspiciousSiteData()
            Toast.makeText(this, "Suspicious site data cleanup ran", Toast.LENGTH_SHORT).show()
        }

        dataSaverButton = largeButton(dataSaverButtonLabel()) {
            val updatedMode = dataSaverModeStore.snapshot().next()
            settingsStore.dataSaverMode = updatedMode
            dataSaverModeStore.update(updatedMode)
            tabRuntimes.values.forEach { runtime ->
                val context = runtime.client.topLevelContextSnapshot()
                WebViewConfigurator.applyDataSaverPolicy(
                    runtime.webView,
                    updatedMode,
                    context.profile,
                    blockerEngine.classifyPageType(context.profile, context.url ?: context.profile.startUrl),
                )
            }
            dataSaverButton.text = dataSaverButtonLabel()
            updateDataUsageReadout()
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.DATA_SAVER,
                    message = "Data Saver mode=${updatedMode.name}",
                    detail = "networkImagesBlocked=${webView.settings.blockNetworkImage}",
                ),
            )
            if (updatedMode == DataSaverMode.MAX) {
                Toast.makeText(this, "MAX may reduce page images", Toast.LENGTH_SHORT).show()
            }
        }

        adaptiveModeButton = largeButton(adaptiveModeButtonLabel()) {
            val updatedMode = adaptiveShieldController.mode().next()
            settingsStore.adaptiveShieldMode = updatedMode
            adaptiveShieldController.updateMode(updatedMode)
            adaptiveModeButton.text = adaptiveModeButtonLabel()
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.ADAPTIVE_OBSERVE,
                    message = "Adaptive Shield mode=${updatedMode.name}",
                    detail = "blockingActive=${settingsStore.blockerEnabled && updatedMode == AdaptiveShieldMode.AUTO_SAFE}",
                ),
            )
        }
        val adaptiveStatusButton = largeButton("Adaptive Shield") {
            collapseShieldPanel()
            showAdaptiveShieldDialog()
        }

        dataUsageText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(dp(12), dp(2), dp(12), dp(4))
            maxLines = 2
        }
        updateDataUsageReadout()

        searchProviderButton = largeButton(searchProviderButtonLabel()) {
            val provider = settingsStore.searchProvider.next()
            settingsStore.searchProvider = provider
            searchProviderButton.text = searchProviderButtonLabel()
            Toast.makeText(this, "Search provider: ${provider.displayName}", Toast.LENGTH_SHORT).show()
        }

        val debugSwitch = Switch(this).apply {
            text = "Debug updates"
            textSize = 15f
            minimumHeight = dp(48)
            isChecked = settingsStore.debugEnabled
            setOnCheckedChangeListener { _, enabled ->
                settingsStore.debugEnabled = enabled
                updateDebugLog()
            }
        }

        markerText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(dp(12), 0, dp(12), dp(4))
            maxLines = 3
        }

        debugTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val clearLogButton = smallButton("Clear") {
            eventLog.clear()
            updateDebugLog()
            updateRuntimeMarkers()
        }

        val copyLogButton = smallButton("Copy") {
            copyDebugLog()
        }

        filterButton = smallButton("Filter: All") {
            cycleDebugFilter()
        }

        listOf(clearLogButton, copyLogButton, filterButton).forEach { control ->
            debugTools.addView(
                control,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        debugPanel = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(243, 244, 246))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        debugPanel.addView(logText)

        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        contentLayer.addView(webView)
        browseHome = buildBrowseHome().apply { visibility = View.GONE }
        contentLayer.addView(browseHome)

        val openDebugButton = largeButton("Open Debug Logs") {
            shieldUiState = shieldUiState.openDebug()
            updateDataUsageReadout()
            updateRuntimeMarkers()
            updateDebugLog()
            syncShieldUi()
        }

        val closeShieldButton = largeButton("Close") {
            collapseShieldPanel()
        }

        shieldPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 250))
            setPadding(dp(16), dp(14), dp(16), dp(16))
            elevation = dp(8).toFloat()
            isClickable = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                setMargins(dp(28), dp(24), dp(28), dp(24))
            }
        }.apply {
            addView(domainText)
            addView(controlRow(backButton, forwardButton, reloadButton))
            addView(controlRow(browseButton, tabsButton, downloadsButton))
            addView(profileButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(controlRow(cleanupButton, dataCleanButton))
            addView(blockerSwitch)
            addView(dataSaverButton)
            addView(controlRow(adaptiveModeButton, adaptiveStatusButton))
            addView(searchProviderButton)
            addView(dataUsageText)
            addView(debugSwitch)
            addView(openDebugButton)
            addView(closeShieldButton)
        }
        contentLayer.addView(shieldPanel)

        val debugHeading = TextView(this).apply {
            text = "Debug Logs"
            textSize = 18f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val closeDebugButton = largeButton("Close Debug Logs") {
            shieldUiState = shieldUiState.closeDebug()
            syncShieldUi()
        }
        val debugCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 250))
            setPadding(dp(14), dp(12), dp(14), dp(14))
            elevation = dp(12).toFloat()
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
            addView(debugHeading)
            addView(markerText)
            addView(debugTools)
            addView(debugPanel)
            addView(closeDebugButton)
        }
        debugOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(72, 0, 0, 0))
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(debugCard)
        }
        contentLayer.addView(debugOverlay)
        contentLayer.addOnLayoutChangeListener { _, _, _, right, bottom, _, _, _, _ ->
            if (right <= 0 || bottom <= 0) return@addOnLayoutChangeListener
            val params = debugCard.layoutParams as FrameLayout.LayoutParams
            val desiredWidth = (right * 0.9f).toInt()
            val desiredHeight = (bottom * 0.75f).toInt()
            if (params.width != desiredWidth || params.height != desiredHeight) {
                params.width = desiredWidth
                params.height = desiredHeight
                debugCard.layoutParams = params
            }
        }

        shieldControl = smallButton("Shield") {
            shieldUiState = shieldUiState.togglePanel()
            syncShieldUi()
        }.apply {
            contentDescription = "Show or hide Site Shield controls"
            layoutParams = FrameLayout.LayoutParams(dp(86), dp(52), Gravity.END or Gravity.BOTTOM).apply {
                setMargins(dp(12), dp(12), dp(12), dp(28))
            }
        }
        root.addView(shieldControl)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val contentParams = contentLayer.layoutParams as FrameLayout.LayoutParams
            contentParams.topMargin = topSafeInset(insets)
            contentParams.bottomMargin = navigationBarBottomInset(insets)
            contentLayer.layoutParams = contentParams
            val params = shieldControl.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = navigationBarBottomInset(insets) + dp(28)
            shieldControl.layoutParams = params
            insets
        }
        root.requestApplyInsets()
        syncShieldUi()
        updateRuntimeMarkers()
        return root
    }

    private fun controlRow(vararg controls: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            controls.forEach { control ->
                addView(
                    control,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createTabRuntime(tab: BrowserTabState): TabRuntime {
        val profile = SiteProfileRegistry.byId(tab.profileId)
        val tabWebView = WebView(this)
        WebViewConfigurator.configure(tabWebView, profile, dataSaverModeStore.snapshot())
        val tracker = DownloadIntentTracker(clockMs = SystemClock::elapsedRealtime)
        lateinit var runtime: TabRuntime
        val client = SiteShieldWebViewClient(
            context = this,
            settingsStore = settingsStore,
            blockerEngine = blockerEngine,
            dataSaverModeStore = dataSaverModeStore,
            adaptiveShieldController = adaptiveShieldController,
            initialProfile = profile,
            initialUrl = tab.currentUrl,
            onProfileMatched = { matched -> onTabProfileMatched(tab.id, matched) },
            onEvent = ::recordEvent,
            onPageLoaded = { view ->
                injectDomCleanup(view)
                inspectAdaptivePageHealth(view)
            },
            onPageUsageCheckpoint = ::updateDataUsageReadout,
            onTopLevelNavigationStarted = { matched, url ->
                onTabNavigationStarted(tab.id, tabWebView, matched, url)
            },
            onRendererGone = { onTabRendererGone(tab.id) },
            onPageReady = { restorePendingScroll(tab.id) },
        )
        runtime = TabRuntime(tab.id, tabWebView, client, tracker)
        tabWebView.webViewClient = client
        tabWebView.webChromeClient = tabChromeClient(runtime)
        tabWebView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val request = DownloadRequestInfo.fromWebViewCallback(
                url, userAgent, contentDisposition, mimeType, contentLength,
            )
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handleDownloadRequest(tab.id, request)
            } else {
                runOnUiThread { handleDownloadRequest(tab.id, request) }
            }
        }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (tabManager.selectedTabId == tab.id) {
                    shieldUiState = shieldUiState.onWebViewDoubleTap()
                    syncShieldUi()
                }
                return true
            }
        })
        tabWebView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) tracker.recordGesture()
            detector.onTouchEvent(event)
            false
        }
        tabRuntimes[tab.id] = runtime
        tabManager.markLive(tab.id)
        if (tab.scrollY > 0) pendingScrollRestores[tab.id] = tab.scrollY
        return runtime
    }

    private fun tabChromeClient(runtime: TabRuntime): WebChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            tabManager.updateTitle(runtime.tabId, title)
            scheduleSessionPersistence()
            if (tabManager.selectedTabId == runtime.tabId) updateHeader(title)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val message = consoleMessage.message()
            if (!message.startsWith(DOM_LOG_PREFIX)) return super.onConsoleMessage(consoleMessage)
            recordEvent(DebugEvent(DebugEventCategory.DOM_CLEANUP, message.removePrefix(DOM_LOG_PREFIX).trim()))
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
        ): Boolean {
            val sourceContext = runtime.client.topLevelContextSnapshot()
            if (!isUserGesture || resultMsg == null) {
                recordEvent(
                    DebugEvent(
                        DebugEventCategory.POPUP_BLOCK,
                        "[${sourceContext.profile.displayName}] Blocked new-window request",
                        "tab=${runtime.tabId}, sourceProfile=${sourceContext.profile.id}, " +
                            "sourcePageType=${blockerEngine.classifyPageType(sourceContext.profile, sourceContext.url)}, " +
                            "hasGesture=$isUserGesture, onCreateWindow=true, actionView=false, loadUrl=false",
                    ),
                )
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popupView = WebView(this@MainActivity)
            popupView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(popup: WebView, request: WebResourceRequest): Boolean =
                    openPopupInTab(runtime, popup, request.url.toString(), sourceContext, isUserGesture)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(popup: WebView, url: String): Boolean =
                    openPopupInTab(runtime, popup, url, sourceContext, isUserGesture)
            }
            transport.webView = popupView
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun activateRuntime(runtime: TabRuntime) {
        webView = runtime.webView
        webViewClient = runtime.client
        activeProfile = runtime.client.topLevelContextSnapshot().profile
    }

    private fun selectTab(tabId: String, checkpointCurrent: Boolean = true) {
        if (checkpointCurrent) checkpointActiveScroll()
        tabRuntimes[tabManager.selectedTabId]?.webView?.onPause()
        val selected = tabManager.select(tabId) ?: return
        val wasSuspended = tabRuntimes[tabId] == null
        val runtime = tabRuntimes[tabId] ?: createTabRuntime(selected)
        (runtime.webView.parent as? ViewGroup)?.removeView(runtime.webView)
        if (::contentLayer.isInitialized) contentLayer.addView(runtime.webView, 0)
        activateRuntime(runtime)
        runtime.webView.onResume()
        settingsStore.selectedProfileId = activeProfile.id
        dataUsageTracker.switchProfile(activeProfile.id)
        browseHome.visibility = if (selected.currentUrl == null) View.VISIBLE else View.GONE
        if (wasSuspended && selected.currentUrl != null) {
            runtime.client.prepareExplicitNavigation(selected.currentUrl)
            runtime.webView.loadUrl(selected.currentUrl)
        }
        updateHeader(selected.title)
        updateTabsButton()
        suspendExcessRuntimes()
        scheduleSessionPersistence()
    }

    private fun closeTab(tabId: String) {
        if (tabId == tabManager.selectedTabId) checkpointActiveScroll()
        tabRuntimes.remove(tabId)?.let(::destroyRuntime)
        val result = tabManager.close(tabId) ?: return
        selectTab(result.selectedTabId, checkpointCurrent = false)
        updateTabsButton()
        scheduleSessionPersistence()
    }

    private fun suspendExcessRuntimes(allBackground: Boolean = false) {
        val candidates = if (allBackground) {
            tabRuntimes.keys.filter { it != tabManager.selectedTabId }
        } else {
            tabManager.suspensionCandidates()
        }
        candidates.forEach { id -> tabRuntimes.remove(id)?.let(::destroyRuntime) }
    }

    private fun destroyRuntime(runtime: TabRuntime) {
        tabManager.updateScroll(runtime.tabId, runtime.webView.scrollY)
        tabManager.markSuspended(runtime.tabId)
        runtime.downloadIntentTracker.clear()
        pendingScrollRestores.remove(runtime.tabId)
        (runtime.webView.parent as? ViewGroup)?.removeView(runtime.webView)
        runtime.webView.stopLoading()
        runtime.webView.setDownloadListener(null)
        runtime.webView.webChromeClient = null
        runtime.webView.webViewClient = WebViewClient()
        runtime.webView.destroy()
    }

    private fun onTabRendererGone(tabId: String) {
        val runtime = tabRuntimes.remove(tabId) ?: return
        destroyRuntime(runtime)
        scheduleSessionPersistence()
        if (tabId == tabManager.selectedTabId) {
            persistenceHandler.post { selectTab(tabId, checkpointCurrent = false) }
        }
    }

    private fun showTabsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        lateinit var dialog: android.app.AlertDialog
        tabManager.allTabs().forEach { tab ->
            val selected = tab.id == tabManager.selectedTabId
            val state = if (tabManager.runtimeState(tab.id) == BrowserTabRuntimeState.LIVE) "live" else "suspended"
            val label = "${if (selected) "●" else "○"} ${tab.title ?: SiteProfileRegistry.byId(tab.profileId).displayName}\n" +
                "${tab.currentUrl?.hostFromUrl() ?: "Browse Home"} · $state"
            val row = controlRow(
                largeButton(label) { dialog.dismiss(); selectTab(tab.id) },
                smallButton("Close") { dialog.dismiss(); closeTab(tab.id); showTabsDialog() },
            )
            content.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Tabs (${tabManager.allTabs().size}/${BrowserTabConfig.MAX_TABS})")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("+ New Tab", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val result = tabManager.createTab()) {
                    CreateTabResult.LimitReached -> Toast.makeText(
                        this, "Maximum ${BrowserTabConfig.MAX_TABS} tabs reached", Toast.LENGTH_SHORT,
                    ).show()
                    is CreateTabResult.Created -> {
                        dialog.dismiss()
                        updateTabsButton()
                        selectTab(result.tab.id)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun updateTabsButton() {
        if (::tabsButton.isInitialized) tabsButton.text = "Tabs (${tabManager.allTabs().size})"
    }

    private fun checkpointActiveScroll() {
        if (::webView.isInitialized && ::tabManager.isInitialized) {
            tabManager.updateScroll(tabManager.selectedTabId, webView.scrollY)
        }
    }

    private fun restorePendingScroll(tabId: String) {
        val scrollY = pendingScrollRestores.remove(tabId) ?: return
        persistenceHandler.postDelayed({
            tabRuntimes[tabId]?.webView?.scrollTo(0, scrollY)
        }, BrowserTabConfig.SCROLL_RESTORE_DELAY_MS)
    }

    private fun scheduleSessionPersistence() {
        persistenceHandler.removeCallbacks(persistSessionRunnable)
        persistenceHandler.postDelayed(persistSessionRunnable, BrowserTabConfig.PERSISTENCE_DEBOUNCE_MS)
    }

    private fun persistSessionNow() {
        if (::sessionRepository.isInitialized && ::tabManager.isInitialized) {
            sessionRepository.save(tabManager.snapshot())
        }
    }

    private fun collapseShieldPanel() {
        shieldUiState = shieldUiState.afterTopLevelNavigation()
        syncShieldUi()
    }

    private fun onTabNavigationStarted(
        tabId: String,
        ownerWebView: WebView,
        profile: SiteProfile,
        url: String?,
    ) {
        tabManager.updateNavigation(tabId, url, profile.id)
        WebViewConfigurator.applyDataSaverPolicy(
            ownerWebView,
            dataSaverModeStore.snapshot(),
            profile,
            blockerEngine.classifyPageType(profile, url ?: profile.startUrl),
        )
        scheduleSessionPersistence()
        if (tabId == tabManager.selectedTabId) {
            if (::browseHome.isInitialized) browseHome.visibility = View.GONE
            collapseShieldPanel()
        }
    }

    private fun searchProviderButtonLabel(): String =
        "Search: ${settingsStore.searchProvider.displayName}"

    private fun showOmnibox() {
        val input = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            val currentUrl = webView.url
            if (!currentUrl.isNullOrBlank() && currentUrl.startsWith("http")) {
                setText(currentUrl)
                selectAll()
            }
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Browse")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (navigateFromOmnibox(input.text.toString())) dialog.dismiss()
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    if (navigateFromOmnibox(input.text.toString())) dialog.dismiss()
                    true
                } else {
                    false
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun navigateFromOmnibox(rawInput: String): Boolean {
        val target = OmniboxInputParser.parse(rawInput) ?: return false
        return when (target) {
            is NavigationTarget.Url -> {
                navigateExplicitUrl(target.url)
                true
            }
            is NavigationTarget.SearchQuery -> {
                navigateExplicitUrl(settingsStore.searchProvider.buildSearchUrl(target.query))
                true
            }
            is NavigationTarget.Invalid -> {
                Toast.makeText(this, target.reason, Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun navigateExplicitUrl(url: String) {
        if (::browseHome.isInitialized) browseHome.visibility = View.GONE
        webViewClient.prepareExplicitNavigation(url)
        webView.loadUrl(url)
    }

    private fun openPopupInTab(
        runtime: TabRuntime,
        popupView: WebView,
        url: String,
        sourceContext: TopLevelContext,
        hasUserGesture: Boolean,
    ): Boolean {
        popupView.destroy()
        val target = OmniboxInputParser.parse(url)
        if (
            target is NavigationTarget.Url &&
            runtime.client.allowPopupNavigation(sourceContext, target.url, hasUserGesture)
        ) {
            runtime.client.prepareExplicitNavigation(target.url)
            runtime.webView.loadUrl(target.url)
        } else if (target !is NavigationTarget.Url) {
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.POPUP_BLOCK,
                    message = "Blocked unsupported new-window destination",
                    detail = "sourceProfile=${sourceContext.profile.id}, hasGesture=$hasUserGesture, " +
                        "onCreateWindow=true, actionView=false, loadUrl=false",
                ),
            )
        }
        return true
    }

    private fun navigateHistory(offset: Int) {
        val history = webView.copyBackForwardList()
        val targetIndex = history.currentIndex + offset
        if (targetIndex !in 0 until history.size) return
        webViewClient.prepareHistoryNavigation(history.getItemAtIndex(targetIndex).url)
        webView.goBackOrForward(offset)
    }

    private fun showBrowseHome() {
        webViewClient.prepareExplicitNavigation(GenericWebProfile.profile.startUrl)
        setActiveProfile(GenericWebProfile.profile)
        tabManager.updateNavigation(tabManager.selectedTabId, null, GenericWebProfile.profile.id)
        tabManager.updateTitle(tabManager.selectedTabId, "Browse")
        scheduleSessionPersistence()
        browseHome.visibility = View.VISIBLE
        collapseShieldPanel()
    }

    private fun buildBrowseHome(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(54), dp(28), dp(28))
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = "Site Shield"
            textSize = 28f
            setTextColor(Color.rgb(17, 24, 39))
            gravity = Gravity.CENTER
        })
        val input = EditText(this).apply {
            hint = "Search or enter website"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    navigateFromOmnibox(text.toString())
                    true
                } else false
            }
        }
        content.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(28) })
        content.addView(largeButton("Go / Search") { navigateFromOmnibox(input.text.toString()) })
        content.addView(TextView(this).apply {
            text = "Optimized Sites"
            textSize = 18f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, dp(28), 0, dp(8))
        })
        SiteProfileRegistry.supportedProfiles.forEach { profile ->
            content.addView(largeButton(profile.displayName) { navigateExplicitUrl(profile.startUrl) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        return ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(content)
        }
    }

    private fun syncShieldUi() {
        if (!::shieldControl.isInitialized || !::shieldPanel.isInitialized || !::debugOverlay.isInitialized) return
        val debugOpen = shieldUiState.debugOverlay == DebugOverlayState.OPEN
        val panelOpen = shieldUiState.panel == ShieldPanelState.EXPANDED && !debugOpen
        shieldPanel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        debugOverlay.visibility = if (debugOpen) View.VISIBLE else View.GONE
        shieldControl.visibility = if (
            shieldUiState.visibility == ShieldVisibility.VISIBLE && !debugOpen
        ) View.VISIBLE else View.GONE
        shieldControl.text = if (panelOpen) "Hide" else "Shield"
        if (panelOpen) updateDataUsageReadout()
    }

    private fun dataSaverButtonLabel(): String =
        "Data Saver: ${dataSaverModeStore.snapshot().displayName}"

    private fun adaptiveModeButtonLabel(): String =
        "Adaptive: ${adaptiveShieldController.mode().displayName}"

    private fun showAdaptiveShieldDialog() {
        val profile = activeProfile
        val summary = adaptiveShieldController.summary(profile.id)
        val records = adaptiveShieldController.records(profile.id)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        content.addView(TextView(this).apply {
            text = "${profile.displayName}\n" +
                "Observed: ${summary.observed}  Candidates: ${summary.candidates}\n" +
                "Learned active: ${summary.learnedActive}  Dormant: ${summary.dormant}  " +
                "Rejected: ${summary.rejected}"
            textSize = 15f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, dp(8), 0, dp(10))
        })
        if (records.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No local adaptive evidence for this profile yet."
                textSize = 14f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            records.forEach { record ->
                content.addView(largeButton(adaptiveRuleLabel(record)) {
                    showAdaptiveRuleActions(profile, record)
                })
            }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Adaptive Shield")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Forget Learned Rules", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Forget adaptive data?")
                    .setMessage("Remove candidates, learned rules, and observations for ${profile.displayName}? Cookies and site data are not affected.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forget") { _, _ ->
                        adaptiveShieldController.forget(profile.id)
                        dialog.dismiss()
                        Toast.makeText(this, "Adaptive data forgotten", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        dialog.show()
    }

    private fun showAdaptiveRuleActions(profile: SiteProfile, record: AdaptiveRecord) {
        val actions = buildList {
            if (record.state == AdaptiveCandidateState.LEARNED || record.state == AdaptiveCandidateState.DORMANT) {
                add("Disable learned rule")
            }
            add("Forget rule")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(record.host)
            .setMessage(
                "Type: ${record.type}\nState: ${record.state}\nSeen: ${record.occurrenceCount}\n" +
                    "Confidence: ${record.confidence}\nPath: ${record.path ?: "host only"}",
            )
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "Disable learned rule" -> adaptiveShieldController.disable(profile.id, record.id)
                    else -> adaptiveShieldController.forget(profile.id, record.id)
                }
                Toast.makeText(this, "Adaptive rule updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun adaptiveRuleLabel(record: AdaptiveRecord): String {
        val kind = when (record.type) {
            AdaptiveCandidateType.OFFSITE_REDIRECT_HOST -> "Offsite redirect"
            AdaptiveCandidateType.THIRD_PARTY_REQUEST_HOST -> "Third-party request"
            AdaptiveCandidateType.FIRST_PARTY_LOADER -> "Loader"
            AdaptiveCandidateType.DOM_STRUCTURE -> "DOM candidate (review only)"
        }
        return "${record.state}: ${record.host}\n$kind · Seen ${record.occurrenceCount} · Confidence ${record.confidence}"
    }

    private fun inspectAdaptivePageHealth(view: WebView) {
        if (adaptiveShieldController.mode() != AdaptiveShieldMode.AUTO_SAFE) return
        val context = tabRuntimes.values.firstOrNull { it.webView === view }
            ?.client?.topLevelContextSnapshot() ?: return
        val profile = context.profile
        val pageUrl = view.url ?: context.url
        val pageType = blockerEngine.classifyPageType(profile, pageUrl)
        if (profile.id != MangakakalotProfile.profile.id || pageType != PageType.CHAPTER_READER) return
        val script = """
            (function() {
              var reader = !!document.querySelector('.container-chapter-reader');
              var images = document.querySelectorAll('.container-chapter-reader img').length;
              var navigation = !!document.querySelector('.navi-change-chapter, .panel-navigation, .chapter-select, select');
              return (reader ? '1' : '0') + ',' + images + ',' + (navigation ? '1' : '0');
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { raw ->
            val parts = decodeJavascriptString(raw).split(',')
            if (parts.size != 3) return@evaluateJavascript
            val readerPresent = parts[0] == "1"
            val imageCount = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: return@evaluateJavascript
            val navigationPresent = parts[2] == "1"
            adaptiveShieldController.reportPageHealth(
                AdaptivePageHealth(
                    profileId = profile.id,
                    pageType = pageType,
                    healthy = readerPresent && imageCount > 0 && navigationPresent,
                    readerContainerPresent = readerPresent,
                    chapterImageCount = imageCount,
                    chapterNavigationPresent = navigationPresent,
                ),
            )
        }
    }

    private fun applyDataSaverPolicy(profile: SiteProfile, url: String?) {
        val pageType = blockerEngine.classifyPageType(profile, url ?: profile.startUrl)
        WebViewConfigurator.applyDataSaverPolicy(
            webView = webView,
            mode = dataSaverModeStore.snapshot(),
            profile = profile,
            pageType = pageType,
        )
    }

    private fun updateDataUsageReadout() {
        if (!::dataUsageText.isInitialized || !::dataUsageTracker.isInitialized) return
        val usage = dataUsageTracker.snapshot()
        dataUsageText.text = if (!usage.countersSupported) {
            "Session data: unavailable"
        } else {
            "Session data: ${formatDataBytes(usage.session.totalBytes)}\n" +
                "RX ${formatDataBytes(usage.session.rxBytes)}  TX ${formatDataBytes(usage.session.txBytes)}" +
                "  App-wide; per-tab bytes unavailable"
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }

    private fun largeButton(label: String, onClick: () -> Unit): Button =
        smallButton(label, onClick).apply {
            textSize = 15f
            minimumHeight = dp(50)
        }

    private fun handleDownloadRequest(tabId: String, request: DownloadRequestInfo?) {
        val runtime = tabRuntimes[tabId] ?: return
        val sourceContext = runtime.client.topLevelContextSnapshot()
        val sourceProfile = sourceContext.profile
        if (request == null) {
            recordEvent(DebugEvent(DebugEventCategory.DOWNLOAD, "download-blocked", "reason=missing-url"))
            Toast.makeText(this, "This download is not supported", Toast.LENGTH_SHORT).show()
            return
        }
        val policy = DownloadPolicy.decide(request.url)
        if (policy is DownloadPolicyDecision.Block) {
            runtime.downloadIntentTracker.clear()
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.DOWNLOAD,
                    message = "download-blocked",
                    detail = "host=${policy.host ?: "unknown"}, tab=$tabId, profile=${sourceProfile.id}, reason=${policy.reason}",
                ),
            )
            val message = if (policy.reason == DownloadBlockReason.UNSUPPORTED_INLINE_DATA) {
                "This type of download is not supported yet"
            } else {
                "Download blocked for safety"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val blockerDecision = if (settingsStore.blockerEnabled) {
            blockerEngine.resourceDecision(sourceProfile, request.url, sourceContext.url)
        } else {
            BlockDecision.Allow
        }
        if (blockerDecision is BlockDecision.Block) {
            runtime.downloadIntentTracker.clear()
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.DOWNLOAD,
                    message = "download-blocked",
                    detail = "host=${(policy as DownloadPolicyDecision.Allow).host}, tab=$tabId, profile=${sourceProfile.id}, " +
                        "reason=blocker-${blockerDecision.reason}, ruleId=${blockerDecision.ruleId ?: "none"}",
                ),
            )
            Toast.makeText(this, "Download blocked by Site Shield", Toast.LENGTH_LONG).show()
            return
        }
        if (!runtime.downloadIntentTracker.consumeIfRecent()) {
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.DOWNLOAD,
                    message = "download-blocked",
                    detail = "host=${(policy as DownloadPolicyDecision.Allow).host}, tab=$tabId, profile=${sourceProfile.id}, reason=no-recent-user-gesture",
                ),
            )
            Toast.makeText(this, "Blocked automatic download", Toast.LENGTH_SHORT).show()
            return
        }

        val prepared = DownloadPreparation.prepare(request)
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.DOWNLOAD,
                message = "download-request",
                detail = "host=${(policy as DownloadPolicyDecision.Allow).host}, mime=${prepared.mimeType}, " +
                    "filename=${prepared.filename}, tab=$tabId, profile=${sourceProfile.id}",
            ),
        )
        val profileId = sourceProfile.id
        val size = request.contentLength?.let(::formatDataBytes) ?: "Unknown"
        val warning = if (prepared.dangerousFileType) {
            "\n\nWarning: This file may be executable or installable. Site Shield will not run or install it."
        } else {
            ""
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage(
                "Filename: ${prepared.filename}\n" +
                    "Type: ${prepared.mimeType}\n" +
                    "Size: $size$warning",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ ->
                when (val result = downloadCoordinator.enqueue(prepared, profileId)) {
                    is DownloadEnqueueResult.Enqueued ->
                        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
                    is DownloadEnqueueResult.Rejected ->
                        Toast.makeText(this, "Download blocked for safety", Toast.LENGTH_LONG).show()
                    is DownloadEnqueueResult.Failed ->
                        Toast.makeText(this, "Could not start download", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showDownloadsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val status = TextView(this).apply {
            text = "Loading downloads…"
            textSize = 15f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, dp(10), 0, dp(10))
        }
        content.addView(status)
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Downloads")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Refresh", null)
            .create()
        fun refresh() {
            status.text = "Loading downloads…"
            downloadCoordinator.queryDownloads { items ->
                if (!dialog.isShowing) return@queryDownloads
                renderDownloadItems(content, status, items, ::refresh)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { refresh() }
            refresh()
        }
        dialog.show()
    }

    private fun renderDownloadItems(
        container: LinearLayout,
        statusView: TextView,
        items: List<DownloadItem>,
        refresh: () -> Unit,
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            statusView.text = "No Site Shield downloads yet"
            container.addView(statusView)
            return
        }
        items.forEach { item ->
            val details = TextView(this).apply {
                textSize = 15f
                setTextColor(Color.rgb(31, 41, 55))
                setPadding(0, dp(10), dp(8), dp(6))
                text = buildString {
                    append(item.record.filename)
                    append("\n")
                    append(downloadStatusLabel(item))
                    item.totalBytes?.let { append(" · ${formatDataBytes(it)}") }
                    append("\nProfile: ${item.record.profileId}")
                }
            }
            container.addView(details)
            when (item.state) {
                DownloadState.COMPLETED -> container.addView(largeButton("Open") {
                    downloadCoordinator.open(item.record.downloadManagerId, item.record.mimeType) { result ->
                        when (result) {
                            DownloadOpenResult.Opened -> Unit
                            DownloadOpenResult.NoHandler -> Toast.makeText(this, "No app can open this file", Toast.LENGTH_LONG).show()
                            DownloadOpenResult.NotCompleted -> Toast.makeText(this, "Download is not complete", Toast.LENGTH_SHORT).show()
                            DownloadOpenResult.Missing -> Toast.makeText(this, "Downloaded file is unavailable", Toast.LENGTH_SHORT).show()
                            is DownloadOpenResult.Failed -> Toast.makeText(this, "Could not open download", Toast.LENGTH_LONG).show()
                        }
                    }
                })
                DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED ->
                    container.addView(largeButton("Cancel") {
                        downloadCoordinator.cancel(item.record.downloadManagerId) { removed ->
                            Toast.makeText(
                                this,
                                if (removed) "Download canceled" else "Could not cancel download",
                                Toast.LENGTH_SHORT,
                            ).show()
                            refresh()
                        }
                    })
                else -> Unit
            }
        }
    }

    private fun downloadStatusLabel(item: DownloadItem): String = when (item.state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.DOWNLOADING -> item.progressPercent?.let { "Downloading · $it%" } ?: "Downloading…"
        DownloadState.PAUSED -> "Paused"
        DownloadState.COMPLETED -> "Completed"
        DownloadState.FAILED -> "Failed"
        DownloadState.UNKNOWN -> "Unknown"
    }

    private fun injectDomCleanup(view: WebView) {
        val context = tabRuntimes.values.firstOrNull { it.webView === view }
            ?.client?.topLevelContextSnapshot() ?: return
        val profile = context.profile
        val pageUrl = view.url ?: context.url ?: profile.startUrl
        val pageType = blockerEngine.classifyPageType(profile, pageUrl)
        val domRules = blockerEngine.domRulesForUrl(profile, pageUrl)
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.DOM_CLEANUP,
                message = "[${profile.displayName}] Reader cleanup requested",
                detail = "pageType=$pageType, url=$pageUrl",
            ),
        )
        val script = buildString {
            append("window.__siteShieldDomConfig = ")
            append(domRules.toJavascriptObject())
            append(";\n")
            append(assets.open("dom_cleanup.js").bufferedReader().use { it.readText() })
        }
        view.evaluateJavascript(script) { result ->
            val cleanupResult = decodeJavascriptString(result)
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.DOM_CLEANUP,
                    message = "[${profile.displayName}] Reader cleanup ran",
                    detail = cleanupResult.ifBlank { "removed=0" },
                ),
            )
        }
    }

    private fun showProfilePicker() {
        val profiles = SiteProfileRegistry.selectableProfiles()
        val labels = profiles.map { it.displayName }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Site profile")
            .setItems(labels) { _, index ->
                val selected = profiles[index]
                setActiveProfile(selected)
                settingsStore.selectedProfileId = selected.id
                recordEvent(
                    DebugEvent(
                        category = DebugEventCategory.PROFILE,
                        message = "Selected ${selected.displayName}",
                        detail = "id=${selected.id}",
                    ),
                )
                if (selected.id == GenericWebProfile.profile.id) {
                    showBrowseHome()
                } else {
                    navigateExplicitUrl(selected.startUrl)
                }
            }
            .show()
    }

    private fun setActiveProfile(profile: SiteProfile) {
        if (::activeProfile.isInitialized && activeProfile.id == profile.id) return
        dataUsageTracker.switchProfile(profile.id)
        activeProfile = profile
        tabManager.updateNavigation(tabManager.selectedTabId, webView.url, profile.id)
        settingsStore.selectedProfileId = profile.id
        WebViewConfigurator.applyCookiePolicy(webView, profile)
        applyDataSaverPolicy(profile, webView.url ?: profile.startUrl)
        if (::profileButton.isInitialized) {
            profileButton.text = "Profile: ${profile.displayName}"
        }
        updateHeader(webView.title)
        collapseShieldPanel()
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.PROFILE,
                message = "Active profile: ${profile.displayName}",
                detail = "id=${profile.id}",
            ),
        )
        updateRuntimeMarkers()
        updateDataUsageReadout()
        scheduleSessionPersistence()
    }

    private fun onTabProfileMatched(tabId: String, profile: SiteProfile) {
        val runtime = tabRuntimes[tabId]
        val url = runtime?.client?.topLevelContextSnapshot()?.url ?: tabManager.tab(tabId)?.currentUrl
        tabManager.updateNavigation(tabId, url, profile.id)
        scheduleSessionPersistence()
        if (tabId != tabManager.selectedTabId) return
        setActiveProfile(profile)
    }

    private fun updateHeader(title: String?) {
        val pageTitle = title?.takeIf { it.isNotBlank() } ?: activeProfile.startUrl.hostFromUrl().orEmpty()
        domainText.text = "${activeProfile.displayName} - $pageTitle"
        updateRuntimeMarkers()
    }

    private fun recordEvent(event: DebugEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { recordEvent(event) }
            return
        }

        eventLog.add(event)
        updateRuntimeMarkers()

        if (settingsStore.debugEnabled) {
            updateDebugLog()
        }
    }

    private fun updateDebugLog() {
        logText.text = eventLog.format(debugFilter)
    }

    private fun updateRuntimeMarkers() {
        if (!::markerText.isInitialized || !::activeProfile.isInitialized || !::webView.isInitialized) return
        val pageUrl = webView.url ?: activeProfile.startUrl
        val pageType = blockerEngine.classifyPageType(activeProfile, pageUrl)
        val policy = blockerEngine.policyForUrl(activeProfile, pageUrl)
        val recent = eventLog.snapshot()
        val navDenied = recent.any { it.category == DebugEventCategory.NAV_BLOCK }
        val cleanupRan = recent.any {
            it.category == DebugEventCategory.DOM_CLEANUP && it.message.contains("cleanup ran", ignoreCase = true)
        }
        val storageRan = recent.any {
            it.category == DebugEventCategory.STORAGE_CLEANUP || it.category == DebugEventCategory.COOKIE_CLEANUP
        }
        markerText.text = "Profile=${activeProfile.id} PageType=$pageType DataSaver=${dataSaverModeStore.snapshot().name} " +
            "Adaptive=${adaptiveShieldController.mode().name} " +
            "OffsiteStrict=${policy.blockOffsiteMainFrameNavigations} " +
            "OffsiteNavDenied=$navDenied ReaderCleanupRan=$cleanupRan StorageCleanupRan=$storageRan"
    }

    private fun copyDebugLog() {
        val text = eventLog.format(debugFilter)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SiteShield debug log", text))
        Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show()
    }

    private fun cycleDebugFilter() {
        val options = listOf<DebugEventCategory?>(null) + DebugEventCategory.entries
        val nextIndex = (options.indexOf(debugFilter) + 1).floorMod(options.size)
        debugFilter = options[nextIndex]
        filterButton.text = "Filter: ${debugFilter?.name ?: "All"}"
        updateDebugLog()
    }

    private fun Int.floorMod(divisor: Int): Int {
        val remainder = this % divisor
        return if (remainder >= 0) remainder else remainder + divisor
    }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return raw
            .removeSurrounding("\"")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun topSafeInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            topSafeInsetPx(
                insets.getInsets(WindowInsets.Type.statusBars()).top,
                insets.getInsets(WindowInsets.Type.displayCutout()).top,
            )
        } else {
            val cutoutTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.safeInsetTop ?: 0
            } else {
                0
            }
            topSafeInsetPx(insets.systemWindowInsetTop, cutoutTop)
        }

    @Suppress("DEPRECATION")
    private fun navigationBarBottomInset(insets: WindowInsets): Int =
        bottomSafeInsetPx(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            insets.systemWindowInsetBottom
        })

    companion object {
        private const val MAX_EVENTS = 200
        private const val DOM_LOG_PREFIX = "[SiteShield]"
    }
}

internal fun topSafeInsetPx(statusBarTop: Int, displayCutoutTop: Int): Int =
    maxOf(statusBarTop, displayCutoutTop)

internal fun bottomSafeInsetPx(navigationBarBottom: Int): Int =
    navigationBarBottom.coerceAtLeast(0)

internal fun DomCleanupRules.toJavascriptObject(): String =
    buildString {
        append("{")
        append("\"suspiciousSelectors\":")
        append(suspiciousSelectors.toJavascriptArray())
        append(",\"preserveSelectors\":")
        append(preserveSelectors.toJavascriptArray())
        append(",\"ancestorCleanupRules\":")
        append(ancestorCleanupRules.toJavascriptRuleArray())
        append(",\"suspiciousClassTokens\":")
        append(suspiciousClassTokens.toJavascriptArray())
        append(",\"suspiciousUrlTokens\":")
        append(suspiciousUrlTokens.toJavascriptArray())
        append(",\"baitTextTokens\":")
        append(baitTextTokens.toJavascriptArray())
        append(",\"junkTextTokens\":")
        append(junkTextTokens.toJavascriptArray())
        append(",\"highZIndexThreshold\":")
        append(highZIndexThreshold)
        append(",\"overlayViewportCoverageThreshold\":")
        append(overlayViewportCoverageThreshold)
        append(",\"enableGenericOverlayHeuristics\":")
        append(enableGenericOverlayHeuristics)
        append("}")
    }

private fun List<String>.toJavascriptArray(): String =
    joinToString(prefix = "[", postfix = "]") { it.toJavascriptString() }

private fun List<AncestorDomCleanupRule>.toJavascriptRuleArray(): String =
    joinToString(prefix = "[", postfix = "]") { rule ->
        buildString {
            append("{\"markerSelector\":")
            append(rule.markerSelector.toJavascriptString())
            append(",\"markerTextPrefixes\":")
            append(rule.markerTextPrefixes.toJavascriptArray())
            append(",\"ancestorSelector\":")
            append(rule.ancestorSelector.toJavascriptString())
            append(",\"ancestorParentSelector\":")
            append(rule.ancestorParentSelector.toJavascriptString())
            append(",\"maxAncestorDepth\":")
            append(rule.maxAncestorDepth)
            append(",\"removalReason\":")
            append(rule.removalReason.toJavascriptString())
            append(",\"neutralizationStrategy\":")
            append(rule.neutralizationStrategy.javascriptValue.toJavascriptString())
            append("}")
        }
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
