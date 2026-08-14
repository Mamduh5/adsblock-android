package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniboxInputParserTest {
    @Test
    fun `recognizes common domains urls and ipv4 with https preference`() {
        mapOf(
            "example.com" to "https://example.com",
            "www.example.com/page" to "https://www.example.com/page",
            "sub.example.com/path" to "https://sub.example.com/path",
            "https://example.com/a?b=c" to "https://example.com/a?b=c",
            "http://example.com" to "http://example.com",
            "192.168.1.1" to "https://192.168.1.1",
        ).forEach { (input, expected) ->
            assertEquals(input, NavigationTarget.Url(expected), OmniboxInputParser.parse(input))
        }
    }

    @Test
    fun `ordinary text with punctuation remains a search query`() {
        listOf(
            "best android browser",
            "phaser camera tutorial",
            "facebook ads blocker",
            "hello world.",
            "ภาษาไทย search query",
        ).forEach { input ->
            assertEquals(input, NavigationTarget.SearchQuery(input), OmniboxInputParser.parse(input))
        }
    }

    @Test
    fun `empty and unsafe explicit schemes fail safely`() {
        assertEquals(null, OmniboxInputParser.parse("   "))
        assertTrue(OmniboxInputParser.parse("javascript:alert(1)") is NavigationTarget.Invalid)
        assertTrue(OmniboxInputParser.parse("https:///missing-host") is NavigationTarget.Invalid)
    }
}
