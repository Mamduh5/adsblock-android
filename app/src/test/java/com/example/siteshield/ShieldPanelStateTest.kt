package com.example.siteshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldPanelStateTest {
    @Test
    fun `toggle opens and closes the shield panel`() {
        assertEquals(ShieldPanelState.EXPANDED, ShieldPanelState.COLLAPSED.toggled())
        assertEquals(ShieldPanelState.COLLAPSED, ShieldPanelState.EXPANDED.toggled())
    }

    @Test
    fun `top level navigation always collapses the shield panel`() {
        assertEquals(
            ShieldPanelState.COLLAPSED,
            ShieldPanelState.EXPANDED.afterTopLevelNavigation(),
        )
        assertEquals(
            ShieldPanelState.COLLAPSED,
            ShieldPanelState.COLLAPSED.afterTopLevelNavigation(),
        )
    }

    @Test
    fun `back is consumed only while the shield panel is expanded`() {
        assertTrue(ShieldPanelState.EXPANDED.consumesBack())
        assertFalse(ShieldPanelState.COLLAPSED.consumesBack())
    }
}
