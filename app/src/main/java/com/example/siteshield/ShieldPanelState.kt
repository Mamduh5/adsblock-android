package com.example.siteshield

enum class ShieldPanelState {
    COLLAPSED,
    EXPANDED,
    ;

    fun toggled(): ShieldPanelState =
        if (this == COLLAPSED) EXPANDED else COLLAPSED

    fun afterTopLevelNavigation(): ShieldPanelState = COLLAPSED

    fun consumesBack(): Boolean = this == EXPANDED
}
