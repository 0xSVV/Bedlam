package ru.shapovalov.bedlam.core.vpn.tile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository

private val Context.quickSettingsTileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "quick_settings_tile",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                produceSharedPreferences = {
                    context.getSharedPreferences(
                        "quick_settings_tile",
                        Context.MODE_PRIVATE,
                    )
                },
            )
        )
    },
)

@Inject
class QuickSettingsTileRepositoryImpl(
    context: Context,
) : QuickSettingsTileRepository {
    private val dataStore = context.applicationContext.quickSettingsTileDataStore

    override val added: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_ADDED] ?: false }

    override suspend fun setAdded(added: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ADDED] = added
        }
    }

    companion object {
        private val KEY_ADDED = booleanPreferencesKey("added")
    }
}
