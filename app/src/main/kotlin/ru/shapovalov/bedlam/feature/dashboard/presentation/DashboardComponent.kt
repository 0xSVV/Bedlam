package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.util.componentScope

class DashboardComponent(
    componentContext: ComponentContext,
    storeFactory: DashboardStoreFactory,
    private val onStartVpn: OnStartVpn,
    private val onStopVpn: OnStopVpn,
    private val onOpenSession: OnOpenSession,
    private val onOpenProfileConfig: OnOpenProfileConfig,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<DashboardStore.State> = store.stateFlow(scope)

    init {
        scope.launch {
            store.labels.collect { label ->
                when (label) {
                    is DashboardStore.Label.RequestStartVpn -> onStartVpn.invoke(label.profile)
                    DashboardStore.Label.RequestStopVpn -> onStopVpn.invoke()
                }
            }
        }
    }

    fun onToggleConnection() = store.accept(DashboardStore.Intent.ToggleConnection)
    fun onSelectProfile(id: String) = store.accept(DashboardStore.Intent.SelectProfile(id))
    fun onDeleteProfile(id: String) = store.accept(DashboardStore.Intent.DeleteProfile(id))
    fun onImportFromClipboard(uri: String) =
        store.accept(DashboardStore.Intent.ImportProfileFromUri(uri))

    fun onDismissError() = store.accept(DashboardStore.Intent.DismissError)
    fun onOpenSession() = onOpenSession.invoke()
    fun onOpenProfileConfig(id: String) = onOpenProfileConfig.invoke(id)
    fun onPingProfile(id: String) = store.accept(DashboardStore.Intent.PingProfile(id))
    fun onPingAllProfiles() = store.accept(DashboardStore.Intent.PingAllProfiles)

    fun interface OnStartVpn {
        fun invoke(profile: Profile)
    }

    fun interface OnStopVpn {
        fun invoke()
    }

    fun interface OnOpenSession {
        fun invoke()
    }

    fun interface OnOpenProfileConfig {
        fun invoke(profileId: String)
    }
}
