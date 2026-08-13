package com.example.siteshield

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val blockerEngine = GenericBlockerEngine()
    private lateinit var settingsStore: SettingsStore
    private lateinit var activeProfile: SiteProfile
    private lateinit var webView: WebView
    private lateinit var domainText: TextView
    private lateinit var profileButton: Button
    private lateinit var logText: TextView
    private lateinit var debugPanel: ScrollView
    private lateinit var debugTools: LinearLayout
    private lateinit var markerText: TextView
    private lateinit var filterButton: Button
    private lateinit var controlScroller: HorizontalScrollView
    private lateinit var readerControl: Button
    private val eventLog = DebugEventLog(MAX_EVENTS)
    private var debugFilter: DebugEventCategory? = null
    private var browserUiMode = BrowserUiMode.NORMAL
    private var readerControlsExpanded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        activeProfile = SiteProfileRegistry.byId(settingsStore.selectedProfileId)

        webView = WebView(this)
        WebViewConfigurator.configure(webView, activeProfile)

        val dataCleaner = SiteDataCleaner(
            webView = webView,
            blockerEngine = blockerEngine,
            currentProfile = { activeProfile },
            onEvent = ::recordEvent,
        )
        val root = buildUi(dataCleaner)
        setContentView(root)
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.PROFILE,
                message = "Active profile: ${activeProfile.displayName}",
                detail = "id=${activeProfile.id}",
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
                if (settingsStore.blockerEnabled) {
                    recordEvent(
                        DebugEvent(
                            category = DebugEventCategory.NAV_BLOCK,
                            message = "[${activeProfile.displayName}] Blocked new-window request",
                            detail = "WebChromeClient.onCreateWindow",
                        ),
                    )
                    return false
                }
                return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
            }
        }

        webView.webViewClient = SiteShieldWebViewClient(
            context = this,
            settingsStore = settingsStore,
            blockerEngine = blockerEngine,
            initialProfile = activeProfile,
            onProfileMatched = ::setActiveProfile,
            onEvent = ::recordEvent,
            onPageLoaded = ::injectDomCleanup,
            onPageTypeChanged = ::applyBrowserUiMode,
        )

        if (savedInstanceState == null) {
            webView.loadUrl(activeProfile.startUrl)
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
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
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

        val browserColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        domainText = TextView(this).apply {
            text = activeProfile.displayName
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(dp(12), dp(8), dp(12), dp(4))
            maxLines = 2
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }

        val backButton = smallButton("Back") {
            if (webView.canGoBack()) webView.goBack()
        }

        val reloadButton = smallButton("Reload") {
            webView.reload()
        }

        val cleanupButton = smallButton("Cleanup") {
            injectDomCleanup(webView)
        }

        profileButton = smallButton(activeProfile.displayName) {
            showProfilePicker()
        }

        val blockerSwitch = Switch(this).apply {
            text = "Blocker"
            textSize = 13f
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

        val debugSwitch = Switch(this).apply {
            text = "Debug"
            textSize = 13f
            isChecked = settingsStore.debugEnabled
            setOnCheckedChangeListener { _, enabled ->
                settingsStore.debugEnabled = enabled
                syncBrowserChromeVisibility()
                updateDebugLog()
            }
        }

        listOf(backButton, reloadButton, cleanupButton, profileButton, blockerSwitch, dataCleanButton, debugSwitch).forEach {
            controls.addView(it)
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
            visibility = if (settingsStore.debugEnabled) View.VISIBLE else View.GONE
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

        listOf(clearLogButton, copyLogButton, filterButton).forEach {
            debugTools.addView(it)
        }

        debugPanel = ScrollView(this).apply {
            visibility = if (settingsStore.debugEnabled) View.VISIBLE else View.GONE
            setBackgroundColor(Color.rgb(243, 244, 246))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(160),
            )
        }

        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        debugPanel.addView(logText)

        webView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )

        controlScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
        }

        browserColumn.addView(domainText)
        browserColumn.addView(controlScroller)
        browserColumn.addView(markerText)
        browserColumn.addView(debugTools)
        browserColumn.addView(debugPanel)
        browserColumn.addView(webView)
        root.addView(browserColumn)

        readerControl = smallButton("Shield") {
            readerControlsExpanded = !readerControlsExpanded
            syncBrowserChromeVisibility()
        }.apply {
            contentDescription = "Show or hide Site Shield reader controls"
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(44), Gravity.END or Gravity.BOTTOM).apply {
                setMargins(dp(12), dp(12), dp(12), dp(16))
            }
        }
        root.addView(readerControl)
        updateRuntimeMarkers()
        return root
    }

    private fun applyBrowserUiMode(pageType: PageType) {
        val nextMode = browserUiModeFor(pageType)
        if (browserUiMode != nextMode) {
            browserUiMode = nextMode
            readerControlsExpanded = false
        }
        syncBrowserChromeVisibility()
    }

    private fun syncBrowserChromeVisibility() {
        if (!::readerControl.isInitialized) return
        val showChrome = browserUiMode == BrowserUiMode.NORMAL || readerControlsExpanded
        val chromeVisibility = if (showChrome) View.VISIBLE else View.GONE
        domainText.visibility = chromeVisibility
        controlScroller.visibility = chromeVisibility
        markerText.visibility = chromeVisibility
        debugTools.visibility = if (showChrome && settingsStore.debugEnabled) View.VISIBLE else View.GONE
        debugPanel.visibility = if (showChrome && settingsStore.debugEnabled) View.VISIBLE else View.GONE
        readerControl.visibility = if (browserUiMode == BrowserUiMode.READER) View.VISIBLE else View.GONE
        readerControl.text = if (readerControlsExpanded) "Hide" else "Shield"
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
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
                webView.loadUrl(selected.startUrl)
            }
            .show()
    }

    private fun setActiveProfile(profile: SiteProfile) {
        if (::activeProfile.isInitialized && activeProfile.id == profile.id) return
        activeProfile = profile
        settingsStore.selectedProfileId = profile.id
        WebViewConfigurator.applyCookiePolicy(webView, profile)
        if (::profileButton.isInitialized) {
            profileButton.text = profile.displayName
        }
        updateHeader(webView.title)
        applyBrowserUiMode(
            blockerEngine.classifyPageType(profile, webView.url ?: profile.startUrl),
        )
        recordEvent(
            DebugEvent(
                category = DebugEventCategory.PROFILE,
                message = "Active profile: ${profile.displayName}",
                detail = "id=${profile.id}",
            ),
        )
        updateRuntimeMarkers()
    }

    private fun updateHeader(title: String?) {
        val pageTitle = title?.takeIf { it.isNotBlank() } ?: activeProfile.startUrl.hostFromUrl().orEmpty()
        val pageType = blockerEngine.classifyPageType(activeProfile, webView.url ?: activeProfile.startUrl)
        val strictMarker = if (pageType == PageType.CHAPTER_READER) " [CHAPTER STRICT]" else ""
        domainText.text = "${activeProfile.displayName}$strictMarker - $pageTitle"
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
        markerText.text = "Profile=${activeProfile.id} PageType=$pageType OffsiteStrict=${policy.blockOffsiteMainFrameNavigations} " +
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

    companion object {
        private const val MAX_EVENTS = 200
        private const val DOM_LOG_PREFIX = "[SiteShield]"
    }
}

private fun DomCleanupRules.toJavascriptObject(): String =
    buildString {
        append("{")
        append("\"suspiciousSelectors\":")
        append(suspiciousSelectors.toJavascriptArray())
        append(",\"preserveSelectors\":")
        append(preserveSelectors.toJavascriptArray())
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
        append("}")
    }

private fun List<String>.toJavascriptArray(): String =
    joinToString(prefix = "[", postfix = "]") { it.toJavascriptString() }

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
