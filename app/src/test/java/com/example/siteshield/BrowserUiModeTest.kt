package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUiModeTest {
    @Test
    fun `chapter pages use reader mode`() {
        assertEquals(BrowserUiMode.READER, browserUiModeFor(PageType.CHAPTER_READER))
    }

    @Test
    fun `non chapter pages use normal mode`() {
        assertEquals(BrowserUiMode.NORMAL, browserUiModeFor(PageType.HOME_LIST_SEARCH))
        assertEquals(BrowserUiMode.NORMAL, browserUiModeFor(PageType.DETAIL))
        assertEquals(BrowserUiMode.NORMAL, browserUiModeFor(PageType.UNKNOWN))
    }
}
