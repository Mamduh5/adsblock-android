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

    var dataSaverMode: DataSaverMode
        get() {
            if (preferences.contains(KEY_DATA_SAVER_MODE)) {
                return DataSaverMode.fromStoredValue(preferences.getString(KEY_DATA_SAVER_MODE, null))
            }
            val initial = DataSaverMode.initialMode(
                hasLegacySettings = listOf(
                    KEY_BLOCKER_ENABLED,
                    KEY_DEBUG_ENABLED,
                    KEY_SELECTED_PROFILE_ID,
                ).any(preferences::contains),
            )
            preferences.edit().putString(KEY_DATA_SAVER_MODE, initial.name).apply()
            return initial
        }
        set(value) = preferences.edit().putString(KEY_DATA_SAVER_MODE, value.name).apply()

    companion object {
        private const val KEY_BLOCKER_ENABLED = "blocker_enabled"
        private const val KEY_DEBUG_ENABLED = "debug_enabled"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val KEY_DATA_SAVER_MODE = "data_saver_mode"
    }
}
