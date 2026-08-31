package com.example.siteshield

import android.content.Context
import java.util.Base64
import java.util.UUID

object BrowserTabConfig {
    const val MAX_TABS = 6
    const val MAX_LIVE_WEB_VIEWS = 3
    const val SESSION_SCHEMA_VERSION = 1
    const val PERSISTENCE_DEBOUNCE_MS = 500L
    const val SCROLL_RESTORE_DELAY_MS = 350L
}

/** Pure attachment ownership used by MainActivity to keep exactly one browser view visible. */
class BrowserViewAttachmentState {
    var attachedTabId: String? = null
        private set

    fun attach(tabId: String): String? {
        val previouslyAttached = attachedTabId
        attachedTabId = tabId
        return previouslyAttached
    }

    fun detach(tabId: String) {
        if (attachedTabId == tabId) attachedTabId = null
    }
}

data class BrowserTabState(
    val id: String,
    val currentUrl: String?,
    val profileId: String,
    val title: String?,
    val lastActiveAt: Long,
    val scrollY: Int = 0,
)

data class BrowserSessionSnapshot(
    val version: Int = BrowserTabConfig.SESSION_SCHEMA_VERSION,
    val selectedTabId: String,
    val tabs: List<BrowserTabState>,
)

enum class BrowserTabRuntimeState {
    LIVE,
    SUSPENDED,
}

sealed interface CreateTabResult {
    data class Created(val tab: BrowserTabState) : CreateTabResult
    data object LimitReached : CreateTabResult
}

data class CloseTabResult(
    val closedTabId: String,
    val selectedTabId: String,
    val replacementTab: BrowserTabState?,
)

class BrowserTabManager(
    restored: BrowserSessionSnapshot?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxTabs: Int = BrowserTabConfig.MAX_TABS,
    private val maxLiveWebViews: Int = BrowserTabConfig.MAX_LIVE_WEB_VIEWS,
) {
    private val tabs = LinkedHashMap<String, BrowserTabState>()
    private val runtimeStates = mutableMapOf<String, BrowserTabRuntimeState>()
    var selectedTabId: String
        private set

    init {
        val validTabs = restored
            ?.takeIf { it.version == BrowserTabConfig.SESSION_SCHEMA_VERSION }
            ?.tabs
            ?.filter { it.id.isNotBlank() }
            ?.distinctBy { it.id }
            ?.take(maxTabs)
            .orEmpty()
        validTabs.forEach { tab ->
            val safeUrl = BrowserSessionUrlSanitizer.sanitize(tab.currentUrl)
            val recovered = tab.copy(
                currentUrl = safeUrl,
                profileId = if (tab.currentUrl != null && safeUrl == null) {
                    GenericWebProfile.profile.id
                } else {
                    resolveRestoredProfileId(safeUrl, tab.profileId)
                },
                title = tab.title?.takeIf { it.isNotBlank() }?.take(200),
                scrollY = tab.scrollY.coerceAtLeast(0),
            )
            tabs[recovered.id] = recovered
            runtimeStates[recovered.id] = BrowserTabRuntimeState.SUSPENDED
        }
        if (tabs.isEmpty()) {
            val initial = newBrowseTab()
            tabs[initial.id] = initial
            runtimeStates[initial.id] = BrowserTabRuntimeState.SUSPENDED
        }
        selectedTabId = restored?.selectedTabId?.takeIf(tabs::containsKey) ?: tabs.keys.first()
        touch(selectedTabId)
    }

    fun allTabs(): List<BrowserTabState> = tabs.values.toList()

    fun selectedTab(): BrowserTabState = checkNotNull(tabs[selectedTabId])

    fun tab(tabId: String): BrowserTabState? = tabs[tabId]

    fun runtimeState(tabId: String): BrowserTabRuntimeState =
        runtimeStates[tabId] ?: BrowserTabRuntimeState.SUSPENDED

    fun createTab(): CreateTabResult {
        if (tabs.size >= maxTabs) return CreateTabResult.LimitReached
        val tab = newBrowseTab()
        tabs[tab.id] = tab
        runtimeStates[tab.id] = BrowserTabRuntimeState.SUSPENDED
        return CreateTabResult.Created(tab)
    }

    fun select(tabId: String): BrowserTabState? {
        if (!tabs.containsKey(tabId)) return null
        selectedTabId = tabId
        touch(tabId)
        return tabs[tabId]
    }

    fun close(tabId: String): CloseTabResult? {
        if (!tabs.containsKey(tabId)) return null
        tabs.remove(tabId)
        runtimeStates.remove(tabId)
        var replacement: BrowserTabState? = null
        if (tabs.isEmpty()) {
            replacement = newBrowseTab()
            tabs[replacement.id] = replacement
            runtimeStates[replacement.id] = BrowserTabRuntimeState.SUSPENDED
        }
        if (tabId == selectedTabId) {
            selectedTabId = tabs.values.maxByOrNull { it.lastActiveAt }!!.id
            touch(selectedTabId)
        }
        return CloseTabResult(tabId, selectedTabId, replacement)
    }

    fun updateNavigation(tabId: String, url: String?, profileId: String) {
        update(tabId) { it.copy(currentUrl = url, profileId = profileId) }
    }

    fun updateTitle(tabId: String, title: String?) {
        update(tabId) { it.copy(title = title?.trim()?.takeIf(String::isNotEmpty)?.take(200)) }
    }

    fun updateScroll(tabId: String, scrollY: Int) {
        update(tabId) { it.copy(scrollY = scrollY.coerceAtLeast(0)) }
    }

    fun markLive(tabId: String) {
        if (tabs.containsKey(tabId)) runtimeStates[tabId] = BrowserTabRuntimeState.LIVE
    }

    fun markSuspended(tabId: String) {
        if (tabs.containsKey(tabId)) runtimeStates[tabId] = BrowserTabRuntimeState.SUSPENDED
    }

    fun suspensionCandidates(): List<String> {
        val excess = runtimeStates.values.count { it == BrowserTabRuntimeState.LIVE } - maxLiveWebViews
        if (excess <= 0) return emptyList()
        return tabs.values
            .asSequence()
            .filter { it.id != selectedTabId && runtimeState(it.id) == BrowserTabRuntimeState.LIVE }
            .sortedBy { it.lastActiveAt }
            .take(excess)
            .map { it.id }
            .toList()
    }

    fun snapshot(): BrowserSessionSnapshot = BrowserSessionSnapshot(
        selectedTabId = selectedTabId,
        tabs = allTabs(),
    )

    private fun touch(tabId: String) {
        update(tabId) { it.copy(lastActiveAt = nowMs()) }
    }

    private fun update(tabId: String, transform: (BrowserTabState) -> BrowserTabState) {
        tabs[tabId]?.let { tabs[tabId] = transform(it) }
    }

    private fun newBrowseTab(): BrowserTabState = BrowserTabState(
        id = newId(),
        currentUrl = null,
        profileId = GenericWebProfile.profile.id,
        title = "Browse",
        lastActiveAt = nowMs(),
    )

    private fun resolveRestoredProfileId(url: String?, savedProfileId: String): String {
        if (!url.isNullOrBlank()) return SiteProfileRegistry.profileForExplicitNavigation(url).id
        return SiteProfileRegistry.selectableProfiles()
            .firstOrNull { it.id == savedProfileId }
            ?.id
            ?: GenericWebProfile.profile.id
    }

}

object BrowserSessionUrlSanitizer {
    /**
     * Session URLs stay only in local app storage. Preserve query and fragment because they can be
     * part of page identity, while rejecting non-HTTPS schemes and stripping user-info credentials.
     */
    fun sanitize(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val parsed = java.net.URI(url)
            require(parsed.scheme.equals("https", ignoreCase = true))
            val host = parsed.host?.takeIf(String::isNotBlank) ?: error("missing host")
            val authorityHost = if (':' in host) "[$host]" else host
            buildString {
                append("https://").append(authorityHost)
                if (parsed.port >= 0) append(':').append(parsed.port)
                append(parsed.rawPath?.takeIf(String::isNotBlank) ?: "/")
                parsed.rawQuery?.let { append('?').append(it) }
                parsed.rawFragment?.let { append('#').append(it) }
            }.also { sanitized -> java.net.URI(sanitized) }
        }.getOrNull()
    }
}

object BrowserSessionCodec {
    fun encode(snapshot: BrowserSessionSnapshot): String = buildString {
        append(snapshot.version).append('\n')
        append(text(snapshot.selectedTabId)).append('\n')
        snapshot.tabs.forEach { tab ->
            append(
                listOf(
                    text(tab.id),
                    nullableText(BrowserSessionUrlSanitizer.sanitize(tab.currentUrl)),
                    text(tab.profileId),
                    nullableText(tab.title),
                    tab.lastActiveAt.toString(),
                    tab.scrollY.toString(),
                ).joinToString("\t"),
            ).append('\n')
        }
    }

    fun decode(raw: String?): BrowserSessionSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val lines = raw.lineSequence().filter(String::isNotBlank).toList()
            val version = lines.first().toInt()
            val selected = decodeText(lines[1])
            val tabs = lines.drop(2).map { line ->
                val fields = line.split('\t')
                require(fields.size == 6)
                BrowserTabState(
                    id = decodeText(fields[0]),
                    currentUrl = decodeNullableText(fields[1]),
                    profileId = decodeText(fields[2]),
                    title = decodeNullableText(fields[3]),
                    lastActiveAt = fields[4].toLong(),
                    scrollY = fields[5].toInt(),
                )
            }
            require(tabs.isNotEmpty())
            BrowserSessionSnapshot(version, selected, tabs)
        }.getOrNull()
    }

    private fun nullableText(value: String?): String = value?.let(::text) ?: "-"
    private fun decodeNullableText(value: String): String? = if (value == "-") null else decodeText(value)
    private fun text(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeText(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}

interface BrowserSessionRepository {
    fun load(): BrowserSessionSnapshot?
    fun save(snapshot: BrowserSessionSnapshot)
}

class SharedPreferencesBrowserSessionRepository(context: Context) : BrowserSessionRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): BrowserSessionSnapshot? = BrowserSessionCodec.decode(
        preferences.getString(KEY_SNAPSHOT, null),
    )

    override fun save(snapshot: BrowserSessionSnapshot) {
        preferences.edit().putString(KEY_SNAPSHOT, BrowserSessionCodec.encode(snapshot)).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "site_shield_browser_session"
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
