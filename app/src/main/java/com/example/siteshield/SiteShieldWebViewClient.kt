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
    private val onEvent: (BlockedEvent) -> Unit,
    private val onPageLoaded: (WebView) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url

        if (!settingsStore.blockerEnabled) return false

        if (BlockerConfig.isSuspiciousNavigation(uri)) {
            onEvent(BlockedEvent("navigation", "Blocked navigation to ${uri.host ?: uri}"))
            if (BlockerConfig.WarnOnSuspiciousNavigation) {
                Toast.makeText(context, "Blocked suspicious navigation", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        if (BlockerConfig.isTargetHost(uri.host)) {
            return false
        }

        if (request.isForMainFrame) {
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
        if (!BlockerConfig.isBlockedResource(uri)) return null

        onEvent(BlockedEvent("resource", "Blocked resource from ${uri.host ?: uri}"))
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
        onEvent(BlockedEvent("page", "Loading ${url.orEmpty()}"))
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
