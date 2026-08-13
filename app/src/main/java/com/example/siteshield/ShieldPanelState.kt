package com.example.siteshield

enum class ShieldPanelState {
    COLLAPSED,
    EXPANDED,
    ;

    fun toggled(): ShieldPanelState =
        if (this == COLLAPSED) EXPANDED else COLLAPSED
}

enum class ShieldVisibility {
    VISIBLE,
    HIDDEN,
}

enum class DebugOverlayState {
    CLOSED,
    OPEN,
}

data class ShieldUiState(
    val visibility: ShieldVisibility = ShieldVisibility.VISIBLE,
    val panel: ShieldPanelState = ShieldPanelState.COLLAPSED,
    val debugOverlay: DebugOverlayState = DebugOverlayState.CLOSED,
) {
    fun togglePanel(): ShieldUiState =
        if (visibility == ShieldVisibility.VISIBLE && debugOverlay == DebugOverlayState.CLOSED) {
            copy(panel = panel.toggled())
        } else {
            this
        }

    fun openDebug(): ShieldUiState = copy(
        panel = ShieldPanelState.COLLAPSED,
        debugOverlay = DebugOverlayState.OPEN,
    )

    fun closeDebug(): ShieldUiState = copy(
        panel = ShieldPanelState.EXPANDED,
        debugOverlay = DebugOverlayState.CLOSED,
    )

    fun onWebViewDoubleTap(): ShieldUiState {
        if (debugOverlay == DebugOverlayState.OPEN) return this
        val nextVisibility = if (visibility == ShieldVisibility.VISIBLE) {
            ShieldVisibility.HIDDEN
        } else {
            ShieldVisibility.VISIBLE
        }
        return copy(
            visibility = nextVisibility,
            panel = ShieldPanelState.COLLAPSED,
        )
    }

    fun afterTopLevelNavigation(): ShieldUiState = copy(
        panel = ShieldPanelState.COLLAPSED,
        debugOverlay = DebugOverlayState.CLOSED,
    )

    fun consumesBack(): Boolean =
        debugOverlay == DebugOverlayState.OPEN || panel == ShieldPanelState.EXPANDED

    fun afterBack(): ShieldUiState =
        when {
            debugOverlay == DebugOverlayState.OPEN -> closeDebug()
            panel == ShieldPanelState.EXPANDED -> copy(panel = ShieldPanelState.COLLAPSED)
            else -> this
        }
}
