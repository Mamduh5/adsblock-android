package com.example.siteshield

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.os.Message
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date

class MainActivity : Activity() {
    private val blockerEngine = GenericBlockerEngine()
    private lateinit var settingsStore: SettingsStore
    private lateinit var activeProfile: SiteProfile
    private lateinit var webView: WebView
    private lateinit var domainText: TextView
    private lateinit var profileButton: Button
    private lateinit var logText: TextView
    private lateinit var debugPanel: ScrollView
    private val events = ArrayDeque<BlockedEvent>()

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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                updateHeader(title)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message()
                if (message.startsWith(DOM_LOG_PREFIX)) {
                    recordEvent(BlockedEvent("dom", message.removePrefix(DOM_LOG_PREFIX).trim()))
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
                    recordEvent(BlockedEvent("popup", "[${activeProfile.displayName}] Blocked new-window request"))
                    return false
                }
                return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
            }
        }

        webView.webViewClient = SiteShieldWebViewClient(
            context = this,
            settingsStore = settingsStore,
            blockerEngine = blockerEngine,
            currentProfile = { activeProfile },
            onProfileMatched = ::setActiveProfile,
            onEvent = ::recordEvent,
            onPageLoaded = ::injectDomCleanup,
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
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

        profileButton = smallButton(activeProfile.displayName) {
            showProfilePicker()
        }

        val blockerSwitch = Switch(this).apply {
            text = "Blocker"
            textSize = 13f
            isChecked = settingsStore.blockerEnabled
            setOnCheckedChangeListener { _, enabled ->
                settingsStore.blockerEnabled = enabled
                recordEvent(BlockedEvent("setting", "Blocker ${if (enabled) "enabled" else "disabled"}"))
                if (enabled) injectDomCleanup(webView)
            }
        }

        val cleanButton = smallButton("Clean") {
            dataCleaner.cleanSuspiciousSiteData()
            Toast.makeText(this, "Suspicious site data cleanup ran", Toast.LENGTH_SHORT).show()
        }

        val debugSwitch = Switch(this).apply {
            text = "Debug"
            textSize = 13f
            isChecked = settingsStore.debugEnabled
            setOnCheckedChangeListener { _, enabled ->
                settingsStore.debugEnabled = enabled
                debugPanel.visibility = if (enabled) View.VISIBLE else View.GONE
                updateDebugLog()
            }
        }

        listOf(backButton, reloadButton, profileButton, blockerSwitch, cleanButton, debugSwitch).forEach {
            controls.addView(it)
        }

        debugPanel = ScrollView(this).apply {
            visibility = if (settingsStore.debugEnabled) View.VISIBLE else View.GONE
            setBackgroundColor(Color.rgb(243, 244, 246))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(136),
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

        val controlScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(controls)
        }

        root.addView(domainText)
        root.addView(controlScroller)
        root.addView(debugPanel)
        root.addView(webView)
        return root
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
        val script = buildString {
            append("window.__siteShieldDomConfig = ")
            append(activeProfile.domRules.toJavascriptObject())
            append(";\n")
            append(assets.open("dom_cleanup.js").bufferedReader().use { it.readText() })
        }
        val profile = activeProfile
        view.evaluateJavascript(script) { result ->
            val removedCount = result?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (removedCount > 0) {
                recordEvent(BlockedEvent("dom", "[${profile.displayName}] Removed or neutralized $removedCount suspicious elements"))
            }
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
                recordEvent(BlockedEvent("profile", "Selected ${selected.displayName}"))
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
        recordEvent(BlockedEvent("profile", "Active profile: ${profile.displayName}"))
    }

    private fun updateHeader(title: String?) {
        val pageTitle = title?.takeIf { it.isNotBlank() } ?: activeProfile.startUrl.hostFromUrl().orEmpty()
        domainText.text = "${activeProfile.displayName} - $pageTitle"
    }

    private fun recordEvent(event: BlockedEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { recordEvent(event) }
            return
        }

        events.addFirst(event)
        while (events.size > MAX_EVENTS) {
            events.removeLast()
        }

        if (settingsStore.debugEnabled) {
            updateDebugLog()
        }
    }

    private fun updateDebugLog() {
        logText.text = events.joinToString(separator = "\n") { event ->
            val time = DateFormat.format("HH:mm:ss", Date(event.timestampMs))
            "$time ${event.type}: ${event.message}"
        }
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
        append(",\"suspiciousClassTokens\":")
        append(suspiciousClassTokens.toJavascriptArray())
        append(",\"suspiciousUrlTokens\":")
        append(suspiciousUrlTokens.toJavascriptArray())
        append(",\"baitTextTokens\":")
        append(baitTextTokens.toJavascriptArray())
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
