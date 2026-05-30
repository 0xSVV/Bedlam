package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository

@Inject
class SettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
) {
    fun create(): SettingsStore =
        object : SettingsStore,
            Store<SettingsStore.Intent, SettingsStore.State, Nothing>
            by storeFactory.create(
                name = "SettingsStore",
                initialState = SettingsStore.State(
                    reliabilitySnapshot = powerReliabilityRepository.snapshotNow(),
                ),
                bootstrapper = SettingsBootstrapper(
                    powerReliabilityRepository = powerReliabilityRepository,
                    quickSettingsTileRepository = quickSettingsTileRepository,
                    refreshIntervalMillis = SETTINGS_REFRESH_MS,
                ),
                executorFactory = {
                    SettingsExecutor(
                        powerReliabilityRepository = powerReliabilityRepository,
                        quickSettingsTileRepository = quickSettingsTileRepository,
                    )
                },
                reducer = SettingsReducer,
            ) {}

    private companion object {
        const val SETTINGS_REFRESH_MS = 1000L
    }
}
