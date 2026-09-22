package ru.shapovalov.bedlam.core.datastore

import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences

object PreferencesCorruption {

    val logReporter: (String, Throwable) -> Unit = { name, error ->
        Log.w(TAG, "Replacing the corrupt $name preferences", error)
    }

    @Volatile
    var reporter: (String, Throwable) -> Unit = logReporter

    fun handler(name: String): ReplaceFileCorruptionHandler<Preferences> =
        ReplaceFileCorruptionHandler { error: CorruptionException ->
            reporter(name, error)
            emptyPreferences()
        }

    private const val TAG = "Preferences"
}
