package com.example.siteshield

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("site_shield_settings", Context.MODE_PRIVATE)

    var blockerEnabled: Boolean
        get() = preferences.getBoolean(KEY_BLOCKER_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_BLOCKER_ENABLED, value).apply()

    var debugEnabled: Boolean
        get() = preferences.getBoolean(KEY_DEBUG_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_DEBUG_ENABLED, value).apply()

    var selectedProfileId: String
        get() = preferences.getString(KEY_SELECTED_PROFILE_ID, MangakakalotProfile.profile.id)
            ?: MangakakalotProfile.profile.id
        set(value) = preferences.edit().putString(KEY_SELECTED_PROFILE_ID, value).apply()

    companion object {
        private const val KEY_BLOCKER_ENABLED = "blocker_enabled"
        private const val KEY_DEBUG_ENABLED = "debug_enabled"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    }
}
