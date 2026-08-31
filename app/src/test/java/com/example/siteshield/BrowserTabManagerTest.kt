package com.example.siteshield

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabManagerTest {
    private fun manager(
        restored: BrowserSessionSnapshot? = null,
        maxTabs: Int = 6,
        maxLive: Int = 3,
    ): BrowserTabManager {
        var time = 100L
        var id = 0
        return BrowserTabManager(
            restored = restored,
            nowMs = { ++time },
            newId = { "tab-${++id}" },
            maxTabs = maxTabs,
            maxLiveWebViews = maxLive,
        )
    }

    @Test
    fun `fresh session has one browse tab with a stable id`() {
        val manager = manager()
        val tab = manager.selectedTab()

        assertEquals(1, manager.allTabs().size)
        assertEquals("tab-1", tab.id)
        assertEquals("generic-web", tab.profileId)
        assertNull(tab.currentUrl)
    }

    @Test
    fun `tabs with the same URL retain different identities and order`() {
        val manager = manager()
        val first = manager.selectedTab()
        val second = (manager.createTab() as CreateTabResult.Created).tab
        manager.updateNavigation(first.id, "https://example.com/", "generic-web")
        manager.updateNavigation(second.id, "https://example.com/", "generic-web")

        assertNotEquals(first.id, second.id)
        assertEquals(listOf(first.id, second.id), manager.allTabs().map { it.id })
    }

    @Test
    fun `selection is explicit and preserves independent profile state`() {
        val manager = manager()
        val manga = manager.selectedTab()
        val facebook = (manager.createTab() as CreateTabResult.Created).tab
        manager.updateNavigation(manga.id, "https://www.mangakakalot.gg/chapter/a", "mangakakalot")
        manager.updateNavigation(facebook.id, "https://facebook.com/", "facebook")

        assertEquals(manga.id, manager.selectedTabId)
        manager.select(facebook.id)
        assertEquals("facebook", manager.selectedTab().profileId)
        assertEquals("mangakakalot", manager.tab(manga.id)?.profileId)
    }

    @Test
    fun `tab limit rejects creation without closing another tab`() {
        val manager = manager(maxTabs = 2)
        assertTrue(manager.createTab() is CreateTabResult.Created)
        assertEquals(CreateTabResult.LimitReached, manager.createTab())
        assertEquals(2, manager.allTabs().size)
    }

    @Test
    fun `closing selected tab chooses most recently active remaining tab`() {
        val manager = manager()
        val first = manager.selectedTab()
        val second = (manager.createTab() as CreateTabResult.Created).tab
        val third = (manager.createTab() as CreateTabResult.Created).tab
        manager.select(second.id)
        manager.select(third.id)
        manager.select(second.id)

        val result = manager.close(second.id)!!

        assertEquals(third.id, result.selectedTabId)
        assertFalse(manager.allTabs().any { it.id == second.id })
        assertNotNull(manager.tab(first.id))
    }

    @Test
    fun `closing last tab creates a clean browse replacement`() {
        val manager = manager()
        val result = manager.close(manager.selectedTabId)!!

        assertNotNull(result.replacementTab)
        assertEquals(1, manager.allTabs().size)
        assertNull(manager.selectedTab().currentUrl)
        assertEquals("generic-web", manager.selectedTab().profileId)
    }

    @Test
    fun `LRU suspension excludes active tab and respects live budget`() {
        val manager = manager(maxLive = 2)
        val first = manager.selectedTab()
        val second = (manager.createTab() as CreateTabResult.Created).tab
        val third = (manager.createTab() as CreateTabResult.Created).tab
        manager.markLive(first.id)
        manager.select(second.id)
        manager.markLive(second.id)
        manager.select(third.id)
        manager.markLive(third.id)

        assertEquals(listOf(first.id), manager.suspensionCandidates())
        assertFalse(manager.suspensionCandidates().contains(manager.selectedTabId))
    }

    @Test
    fun `suspension changes runtime state without discarding tab metadata`() {
        val manager = manager()
        val tab = manager.selectedTab()
        manager.updateNavigation(tab.id, "https://www.mangakakalot.gg/chapter/a", "mangakakalot")
        manager.updateTitle(tab.id, "Chapter A")
        manager.updateScroll(tab.id, 900)
        manager.markLive(tab.id)

        manager.markSuspended(tab.id)

        assertEquals(BrowserTabRuntimeState.SUSPENDED, manager.runtimeState(tab.id))
        assertEquals("https://www.mangakakalot.gg/chapter/a", manager.tab(tab.id)?.currentUrl)
        assertEquals("mangakakalot", manager.tab(tab.id)?.profileId)
        assertEquals("Chapter A", manager.tab(tab.id)?.title)
        assertEquals(900, manager.tab(tab.id)?.scrollY)
    }

    @Test
    fun `normal WebView cache mode remains LOAD_DEFAULT`() {
        assertEquals(WebSettings.LOAD_DEFAULT, WebViewConfigurator.NORMAL_CACHE_MODE)
    }

    @Test
    fun `snapshot codec restores order selected tab and lightweight fields`() {
        val snapshot = BrowserSessionSnapshot(
            selectedTabId = "b",
            tabs = listOf(
                BrowserTabState("a", "https://facebook.com/", "facebook", "Home", 10, 20),
                BrowserTabState("b", "https://www.mangakakalot.gg/chapter/a", "mangakakalot", "Chapter", 30, 400),
            ),
        )

        val decoded = BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        val restored = manager(decoded)
        assertEquals(listOf("a", "b"), restored.allTabs().map { it.id })
        assertEquals("b", restored.selectedTabId)
        assertEquals(400, restored.selectedTab().scrollY)
        assertTrue(restored.allTabs().all { restored.runtimeState(it.id) == BrowserTabRuntimeState.SUSPENDED })
    }

    @Test
    fun `corrupt or unsupported snapshots recover to one browse tab`() {
        assertNull(BrowserSessionCodec.decode("not a session"))
        val unsupported = BrowserSessionSnapshot(99, "old", listOf(
            BrowserTabState("old", "https://facebook.com/", "facebook", null, 1),
        ))

        val restored = manager(unsupported)

        assertEquals(1, restored.allTabs().size)
        assertNull(restored.selectedTab().currentUrl)
    }

    @Test
    fun `restore resolves URL using current registry instead of stale profile hint`() {
        val restored = manager(
            BrowserSessionSnapshot(
                selectedTabId = "known",
                tabs = listOf(
                    BrowserTabState(
                        "known",
                        "https://www.mangakakalot.gg/chapter/a",
                        "deleted-profile",
                        null,
                        1,
                    ),
                    BrowserTabState("unknown", "https://example.com/", "facebook", null, 2),
                ),
            ),
        )

        assertEquals("mangakakalot", restored.tab("known")?.profileId)
        assertEquals("generic-web", restored.tab("unknown")?.profileId)
    }

    @Test
    fun `unsafe restored URLs are discarded without reusing obsolete profile`() {
        val restored = manager(
            BrowserSessionSnapshot(
                selectedTabId = "bad",
                tabs = listOf(BrowserTabState("bad", "javascript:alert(1)", "facebook", null, 1)),
            ),
        )

        assertNull(restored.selectedTab().currentUrl)
        assertEquals("generic-web", restored.selectedTab().profileId)
        assertNull(BrowserSessionUrlSanitizer.sanitize("data:text/html,unsafe"))
        assertNull(BrowserSessionUrlSanitizer.sanitize("intent://example.com"))
        assertNull(BrowserSessionUrlSanitizer.sanitize("http://example.com/insecure"))
    }

    @Test
    fun `persisted URLs preserve page identity but omit user info`() {
        val snapshot = BrowserSessionSnapshot(
            selectedTabId = "safe",
            tabs = listOf(
                BrowserTabState(
                    "safe",
                    "https://user:secret@example.com/article?q=private&token=secret#section",
                    "generic-web",
                    "Article",
                    1,
                ),
            ),
        )

        val decoded = BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot))

        assertEquals(
            "https://example.com/article?q=private&token=secret#section",
            decoded?.tabs?.single()?.currentUrl,
        )
    }

    @Test
    fun `youtube watch query survives codec and manager restore`() {
        val url = "https://youtube.com/watch?v=abc123&list=PL%2F42&t=90#details"
        val snapshot = BrowserSessionSnapshot(
            selectedTabId = "video",
            tabs = listOf(BrowserTabState("video", url, "youtube", "Video", 1)),
        )

        val restored = manager(BrowserSessionCodec.decode(BrowserSessionCodec.encode(snapshot)))

        assertEquals(url, restored.selectedTab().currentUrl)
        assertEquals("youtube", restored.selectedTab().profileId)
    }

    @Test
    fun `only one logical browser attachment is owned after repeated switches`() {
        val attachment = BrowserViewAttachmentState()

        assertNull(attachment.attach("tab-a"))
        assertEquals("tab-a", attachment.attach("tab-b"))
        assertEquals("tab-b", attachment.attach("tab-c"))
        attachment.detach("tab-b")
        assertEquals("tab-c", attachment.attachedTabId)
        attachment.detach("tab-c")
        assertNull(attachment.attachedTabId)
    }

    @Test
    fun `download gesture tokens cannot cross tabs`() {
        var time = 100L
        val a = DownloadIntentTracker(clockMs = { time })
        val b = DownloadIntentTracker(clockMs = { time })
        a.recordGesture()

        assertFalse(b.consumeIfRecent())
        assertTrue(a.consumeIfRecent())
        assertFalse(a.consumeIfRecent())
    }
}
