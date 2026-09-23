package ru.shapovalov.bedlam.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val mutex = Mutex()
    private val current = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = current

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = mutex.withLock {
        transform(current.value).also { current.value = it }
    }
}
