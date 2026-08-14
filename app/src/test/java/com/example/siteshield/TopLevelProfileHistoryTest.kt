package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopLevelProfileHistoryTest {
    @Test
    fun `history restores recorded generic and optimized ownership`() {
        val history = TopLevelProfileHistory()
        history.remember("https://www.google.com/search?q=test", GenericWebProfile.profile)
        history.remember("https://www.youtube.com/watch?v=test", YouTubeProfile.profile)

        assertEquals("generic-web", history.profileFor("https://www.google.com/search?q=test")?.id)
        assertEquals("youtube", history.profileFor("https://www.youtube.com/watch?v=test")?.id)
        assertNull(history.profileFor("https://unseen.example/"))
    }
}
