package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.latency.PingProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileFromUriUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileIdUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.bedlam.core.vpn.ReconcileConnectionStateUseCase
import ru.shapovalov.bedlam.core.vpn.VpnRuntimeStateRepository
import ru.shapovalov.hysteria.api.HysteriaClient

@Inject
class DashboardStoreFactory(
    private val storeFactory: StoreFactory,
    private val getProfiles: GetProfilesUseCase,
    private val observeActiveId: ObserveActiveProfileIdUseCase,
    private val setActiveProfile: SetActiveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val importFromUri: ImportProfileFromUriUseCase,
    private val client: HysteriaClient,
    private val pingProfile: PingProfileUseCase,
    private val runtimeStateRepository: VpnRuntimeStateRepository,
    private val reconcileConnectionState: ReconcileConnectionStateUseCase,
) {
    fun create(): DashboardStore =
        object : DashboardStore,
            Store<DashboardStore.Intent, DashboardStore.State, DashboardStore.Label>
            by storeFactory.create(
                name = "DashboardStore",
                initialState = DashboardStore.State(connectionState = client.state.value),
                bootstrapper = DashboardBootstrapper(
                    getProfiles,
                    observeActiveId,
                    client,
                    runtimeStateRepository,
                    reconcileConnectionState,
                ),
                executorFactory = {
                    DashboardExecutor(
                        setActiveProfile,
                        deleteProfile,
                        importFromUri,
                        pingProfile
                    )
                },
                reducer = DashboardReducer,
            ) {}
}
