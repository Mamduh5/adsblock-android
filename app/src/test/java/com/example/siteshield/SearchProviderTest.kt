package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProviderTest {
    @Test
    fun `all providers build encoded https search urls`() {
        SearchProvider.entries.forEach { provider ->
            assertTrue(provider.buildSearchUrl("hello world").startsWith("https://"))
            assertTrue(provider.buildSearchUrl("hello world").endsWith("hello+world"))
            assertTrue(provider.buildSearchUrl("A&B").endsWith("A%26B"))
            assertTrue(provider.buildSearchUrl("C++").endsWith("C%2B%2B"))
            assertTrue(provider.buildSearchUrl("question? test").endsWith("question%3F+test"))
            assertTrue(provider.buildSearchUrl("ภาษาไทย").contains("%"))
        }
    }

    @Test
    fun `stored provider values round trip and corrupt values use google`() {
        SearchProvider.entries.forEach { provider ->
            assertEquals(provider, SearchProvider.fromStoredValue(provider.name))
        }
        assertEquals(SearchProvider.GOOGLE, SearchProvider.fromStoredValue(null))
        assertEquals(SearchProvider.GOOGLE, SearchProvider.fromStoredValue("UNKNOWN"))
    }
}
