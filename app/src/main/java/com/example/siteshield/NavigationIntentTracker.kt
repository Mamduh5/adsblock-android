package com.example.siteshield

import java.util.Locale

enum class NavigationIntentCategory {
    APP_EXPLICIT,
    PAGE_LINK_INTENDED,
    PAGE_DRIVEN,
    CLICK_HIJACK_SUSPECTED,
    NO_GESTURE,
}

data class NavigationIntentResult(
    val category: NavigationIntentCategory,
    val trusted: Boolean,
)

class NavigationIntentTracker(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val validityMs: Long = 2_000L,
) {
    private data class Intent(
        val generation: Long,
        val host: String,
        val path: String,
        val targetBlank: Boolean,
        val recordedAtMs: Long,
    )

    private var generation = 0L
    private var channelToken = newToken()
    private var recentIntent: Intent? = null
    private var appOwnedTarget: Pair<String, String>? = null

    @Synchronized fun documentStarted(): Long {
        generation += 1
        channelToken = newToken()
        recentIntent = null
        return generation
    }

    @Synchronized fun generation(): Long = generation
    @Synchronized fun channelToken(): String = channelToken

    @Synchronized fun prepareAppOwned(url: String) {
        normalizedDestination(url)?.let { appOwnedTarget = it }
    }

    @Synchronized
    fun record(generation: Long, token: String, host: String, path: String, targetBlank: Boolean): Boolean {
        val normalizedHost = host.normalizedHost() ?: return false
        if (generation != this.generation || token != channelToken) return false
        recentIntent = Intent(generation, normalizedHost, sanitizeAdaptivePath(path), targetBlank, clockMs())
        return true
    }

    @Synchronized
    fun resolve(url: String, hasGesture: Boolean, popup: Boolean): NavigationIntentResult {
        val destination = normalizedDestination(url)
            ?: return NavigationIntentResult(NavigationIntentCategory.PAGE_DRIVEN, false)
        if (appOwnedTarget == destination) {
            appOwnedTarget = null
            return NavigationIntentResult(NavigationIntentCategory.APP_EXPLICIT, true)
        }
        val nowMs = clockMs()
        val intent = recentIntent
        recentIntent = null
        val fresh = intent != null && intent.generation == generation && nowMs - intent.recordedAtMs in 0..validityMs
        val dispositionMatches = intent != null && (!popup || intent.targetBlank)
        if (fresh && dispositionMatches && intent?.host == destination.first && intent.path == destination.second) {
            return NavigationIntentResult(NavigationIntentCategory.PAGE_LINK_INTENDED, true)
        }
        return when {
            hasGesture -> NavigationIntentResult(NavigationIntentCategory.CLICK_HIJACK_SUSPECTED, false)
            intent != null -> NavigationIntentResult(NavigationIntentCategory.PAGE_DRIVEN, false)
            else -> NavigationIntentResult(NavigationIntentCategory.NO_GESTURE, false)
        }
    }

    private fun normalizedDestination(url: String): Pair<String, String>? {
        val uri = url.toUriOrNull() ?: return null
        val host = uri.host.normalizedHost() ?: return null
        return host.lowercase(Locale.US) to sanitizeAdaptivePath(uri.path)
    }

    private fun newToken(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}

object NavigationIntentMessage {
    private const val PREFIX = "[SiteShieldIntent] N1|"

    data class Parsed(
        val generation: Long,
        val token: String,
        val host: String,
        val path: String,
        val targetBlank: Boolean,
    )

    fun parse(message: String): Parsed? {
        if (!message.startsWith(PREFIX)) return null
        val fields = message.removePrefix(PREFIX).split('|')
        if (fields.size != 5) return null
        val generation = fields[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val token = fields[1].takeIf { it.length == 32 && it.matches(Regex("[a-f0-9]+")) } ?: return null
        val host = fields[2].normalizedHost()?.takeIf {
            it.length <= 253 && it.matches(Regex("[a-z0-9.-]+")) && ".." !in it && !it.startsWith('.')
        } ?: return null
        val path = runCatching { java.net.URLDecoder.decode(fields[3], "UTF-8") }.getOrNull() ?: return null
        if (path.length > 240 || '\n' in path || '\r' in path) return null
        val targetBlank = when (fields[4]) { "1" -> true; "0" -> false; else -> return null }
        return Parsed(generation, token, host, sanitizeAdaptivePath(path), targetBlank)
    }
}
