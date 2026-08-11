package com.example.siteshield

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TopLevelContextStoreTest {
    private val siteA = profile("site-a")
    private val siteB = profile("site-b")
    private val contextA = TopLevelContext("https://site-a.example/chapter/1", siteA)
    private val contextB = TopLevelContext("https://site-b.example/chapter/2", siteB)

    @Test
    fun `snapshot starts with complete initial url and profile pair`() {
        val store = TopLevelContextStore(contextA)

        assertEquals(contextA, store.snapshot())
    }

    @Test
    fun `update replaces url and profile as one immutable snapshot`() {
        val store = TopLevelContextStore(contextA)
        val originalSnapshot = store.snapshot()

        store.update(contextB)

        assertEquals(contextA, originalSnapshot)
        assertEquals(contextB, store.snapshot())
    }

    @Test
    fun `concurrent readers only observe complete context pairs`() {
        val store = TopLevelContextStore(contextA)
        val start = CountDownLatch(1)
        val invalidPairObserved = AtomicBoolean(false)
        val validContexts = setOf(contextA, contextB)

        val writer = thread(start = true) {
            start.await()
            repeat(10_000) { iteration ->
                store.update(if (iteration % 2 == 0) contextB else contextA)
            }
        }
        val reader = thread(start = true) {
            start.await()
            repeat(10_000) {
                if (store.snapshot() !in validContexts) {
                    invalidPairObserved.set(true)
                }
            }
        }

        start.countDown()
        writer.join()
        reader.join()

        assertFalse(invalidPairObserved.get())
    }

    private fun profile(id: String): SiteProfile =
        SiteProfile(
            id = id,
            displayName = id,
            startUrl = "https://$id.example/",
            allowedHosts = listOf(HostPattern.DomainSuffix("$id.example")),
        )
}
