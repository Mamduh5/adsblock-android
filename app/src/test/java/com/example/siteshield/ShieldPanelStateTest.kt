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
    fun `shield is visible with overlays closed by default`() {
        assertEquals(
            ShieldUiState(),
            ShieldUiState(
                visibility = ShieldVisibility.VISIBLE,
                panel = ShieldPanelState.COLLAPSED,
                debugOverlay = DebugOverlayState.CLOSED,
            ),
        )
    }

    @Test
    fun `double tap toggles shield visibility and closes its panel`() {
        val hidden = ShieldUiState(panel = ShieldPanelState.EXPANDED).onWebViewDoubleTap()

        assertEquals(ShieldVisibility.HIDDEN, hidden.visibility)
        assertEquals(ShieldPanelState.COLLAPSED, hidden.panel)
        assertEquals(ShieldVisibility.VISIBLE, hidden.onWebViewDoubleTap().visibility)
    }

    @Test
    fun `navigation collapses overlays without changing shield visibility`() {
        val hidden = ShieldUiState(
            visibility = ShieldVisibility.HIDDEN,
            panel = ShieldPanelState.EXPANDED,
        ).afterTopLevelNavigation()
        val visible = ShieldUiState(
            visibility = ShieldVisibility.VISIBLE,
            panel = ShieldPanelState.EXPANDED,
        ).afterTopLevelNavigation()

        assertEquals(ShieldVisibility.HIDDEN, hidden.visibility)
        assertEquals(ShieldVisibility.VISIBLE, visible.visibility)
        assertEquals(ShieldPanelState.COLLAPSED, hidden.panel)
        assertEquals(ShieldPanelState.COLLAPSED, visible.panel)
    }

    @Test
    fun `back from debug returns to shield panel then closes panel`() {
        val debug = ShieldUiState(panel = ShieldPanelState.EXPANDED).openDebug()
        val returnedPanel = debug.afterBack()
        val collapsed = returnedPanel.afterBack()

        assertTrue(debug.consumesBack())
        assertEquals(DebugOverlayState.CLOSED, returnedPanel.debugOverlay)
        assertEquals(ShieldPanelState.EXPANDED, returnedPanel.panel)
        assertEquals(ShieldPanelState.COLLAPSED, collapsed.panel)
        assertFalse(collapsed.consumesBack())
    }

    @Test
    fun `double tap is ignored while debug owns interaction`() {
        val debug = ShieldUiState().openDebug()

        assertEquals(debug, debug.onWebViewDoubleTap())
    }
}
