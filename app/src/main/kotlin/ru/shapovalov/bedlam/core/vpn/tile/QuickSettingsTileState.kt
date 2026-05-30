package ru.shapovalov.bedlam.core.vpn.tile

import android.content.Context

object QuickSettingsTileState {

    fun isAdded(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ADDED, false)

    fun setAdded(context: Context, added: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_ADDED, added)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "quick_settings_tile"
    private const val KEY_ADDED = "added"
}
