package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository
import ru.shapovalov.bedlam.feature.update.domain.usecase.FetchUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.ObserveAvailableVersionUseCase

@Inject
class SettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
    private val observeAvailableVersion: ObserveAvailableVersionUseCase,
    private val fetchUpdate: FetchUpdateUseCase,
) {
    fun create(): SettingsStore =
        object : SettingsStore,
            Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label>
            by storeFactory.create(
                name = "SettingsStore",
                initialState = SettingsStore.State(),
                bootstrapper = SettingsBootstrapper(
                    powerReliabilityRepository = powerReliabilityRepository,
                    quickSettingsTileRepository = quickSettingsTileRepository,
                    observeAvailableVersion = observeAvailableVersion,
                ),
                executorFactory = {
                    SettingsExecutor(
                        powerReliabilityRepository = powerReliabilityRepository,
                        quickSettingsTileRepository = quickSettingsTileRepository,
                        fetchUpdate = fetchUpdate,
                        reliabilityRefreshMillis = SETTINGS_REFRESH_MS,
                    )
                },
                reducer = SettingsReducer,
            ) {}

    private companion object {
        const val SETTINGS_REFRESH_MS = 1000L
    }
}
