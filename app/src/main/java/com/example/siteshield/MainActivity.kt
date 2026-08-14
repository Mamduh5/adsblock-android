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
    private val blockerEngine = GenericBlockerEngine()
    private lateinit var settingsStore: SettingsStore
    private lateinit var dataSaverModeStore: DataSaverModeStore
    private lateinit var dataUsageTracker: DataUsageTracker
    private lateinit var activeProfile: SiteProfile
    private lateinit var webView: WebView
    private lateinit var webViewClient: SiteShieldWebViewClient
    private lateinit var browseHome: View
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
    private lateinit var dataUsageText: TextView
    private lateinit var searchProviderButton: Button
    private val eventLog = DebugEventLog(MAX_EVENTS)
    private var debugFilter: DebugEventCategory? = null
    private var shieldUiState = ShieldUiState()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        activeProfile = SiteProfileRegistry.byId(settingsStore.selectedProfileId)
        dataSaverModeStore = DataSaverModeStore(settingsStore.dataSaverMode)
        dataUsageTracker = DataUsageTracker(AndroidNetworkCounterProvider, activeProfile.id)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView = WebView(this)
        WebViewConfigurator.configure(webView, activeProfile, dataSaverModeStore.snapshot())

        val dataCleaner = SiteDataCleaner(
            webView = webView,
            blockerEngine = blockerEngine,
            currentProfile = { activeProfile },
            onEvent = ::recordEvent,
        )
        val root = buildUi(dataCleaner)
        setContentView(root)
        observeWebViewDoubleTaps()
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.PROFILE,
                message = "Active profile: ${activeProfile.displayName}",
                detail = "id=${activeProfile.id}",
            ),
        )
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.DATA_SAVER,
                message = "Data Saver mode=${dataSaverModeStore.snapshot().name}",
            ),
        )

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                updateHeader(title)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message()
                if (message.startsWith(DOM_LOG_PREFIX)) {
                    recordEvent(
                        DebugEvent(
                            category = DebugEventCategory.DOM_CLEANUP,
                            message = message.removePrefix(DOM_LOG_PREFIX).trim(),
                        ),
                    )
                    return true
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (!isUserGesture || resultMsg == null) {
                    recordEvent(
                        DebugEvent(
                            category = DebugEventCategory.NAV_BLOCK,
                            message = "[${activeProfile.displayName}] Blocked new-window request",
                            detail = "WebChromeClient.onCreateWindow userGesture=$isUserGesture",
                        ),
                    )
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popupView = WebView(this@MainActivity)
                popupView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        popup: WebView,
                        request: WebResourceRequest,
                    ): Boolean = openPopupInMainView(popup, request.url.toString())

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(popup: WebView, url: String): Boolean =
                        openPopupInMainView(popup, url)
                }
                transport.webView = popupView
                resultMsg.sendToTarget()
                return true
            }
        }

        webViewClient = SiteShieldWebViewClient(
            context = this,
            settingsStore = settingsStore,
            blockerEngine = blockerEngine,
            dataSaverModeStore = dataSaverModeStore,
            initialProfile = activeProfile,
            onProfileMatched = ::setActiveProfile,
            onEvent = ::recordEvent,
            onPageLoaded = ::injectDomCleanup,
            onPageUsageCheckpoint = ::updateDataUsageReadout,
            onTopLevelNavigationStarted = ::onTopLevelNavigationStarted,
        )
        webView.webViewClient = webViewClient

        if (savedInstanceState == null) {
            if (activeProfile.id == GenericWebProfile.profile.id) {
                showBrowseHome()
            } else {
                navigateExplicitUrl(activeProfile.startUrl)
            }
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
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

    override fun onDestroy() {
        dataUsageTracker.flush()
        webView.destroy()
        super.onDestroy()
    }

    private fun buildUi(dataCleaner: SiteDataCleaner): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val contentLayer = FrameLayout(this).apply {
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
            dataCleaner.cleanSuspiciousSiteData()
            Toast.makeText(this, "Suspicious site data cleanup ran", Toast.LENGTH_SHORT).show()
        }

        dataSaverButton = largeButton(dataSaverButtonLabel()) {
            val updatedMode = dataSaverModeStore.snapshot().next()
            settingsStore.dataSaverMode = updatedMode
            dataSaverModeStore.update(updatedMode)
            applyDataSaverPolicy(activeProfile, webView.url)
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
            addView(browseButton)
            addView(profileButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(controlRow(cleanupButton, dataCleanButton))
            addView(blockerSwitch)
            addView(dataSaverButton)
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

    private fun collapseShieldPanel() {
        shieldUiState = shieldUiState.afterTopLevelNavigation()
        syncShieldUi()
    }

    private fun onTopLevelNavigationStarted(profile: SiteProfile, url: String?) {
        if (::browseHome.isInitialized) browseHome.visibility = View.GONE
        applyDataSaverPolicy(profile, url)
        collapseShieldPanel()
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

    private fun openPopupInMainView(popupView: WebView, url: String): Boolean {
        popupView.destroy()
        val target = OmniboxInputParser.parse(url)
        if (target is NavigationTarget.Url) {
            navigateExplicitUrl(target.url)
        } else {
            recordEvent(
                DebugEvent(
                    category = DebugEventCategory.NAV_BLOCK,
                    message = "Blocked unsupported new-window destination",
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
            val activeUsage = usage.byProfile[activeProfile.id] ?: DataUsage()
            "Session data: ${formatDataBytes(usage.session.totalBytes)}\n" +
                "RX ${formatDataBytes(usage.session.rxBytes)}  TX ${formatDataBytes(usage.session.txBytes)}" +
                "  ${activeProfile.displayName} ${formatDataBytes(activeUsage.totalBytes)}"
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

    @SuppressLint("ClickableViewAccessibility")
    private fun observeWebViewDoubleTaps() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                shieldUiState = shieldUiState.onWebViewDoubleTap()
                syncShieldUi()
                return true
            }
        })
        webView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    private fun injectDomCleanup(view: WebView) {
        val pageUrl = view.url ?: activeProfile.startUrl
        val pageType = blockerEngine.classifyPageType(activeProfile, pageUrl)
        val domRules = blockerEngine.domRulesForUrl(activeProfile, pageUrl)
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.DOM_CLEANUP,
                message = "[${activeProfile.displayName}] Reader cleanup requested",
                detail = "pageType=$pageType, url=$pageUrl",
            ),
        )
        val script = buildString {
            append("window.__siteShieldDomConfig = ")
            append(domRules.toJavascriptObject())
            append(";\n")
            append(assets.open("dom_cleanup.js").bufferedReader().use { it.readText() })
        }
        val profile = activeProfile
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
