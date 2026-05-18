package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile

@Inject
class DashboardComponent(
    storeProviderProvider: () -> DashboardStoreProvider,
    @Assisted componentContext: ComponentContext,
    @Assisted private val onStartVpn: OnStartVpn,
    @Assisted private val onStopVpn: OnStopVpn,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeProviderProvider().provide() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        lifecycle.doOnDestroy { scope.cancel() }
    }

    fun onToggleConnection() = store.accept(DashboardStore.Intent.ToggleConnection)
    fun onSelectProfile(id: String) = store.accept(DashboardStore.Intent.SelectProfile(id))
    fun onDeleteProfile(id: String) = store.accept(DashboardStore.Intent.DeleteProfile(id))
    fun onImportFromClipboard(uri: String) = store.accept(DashboardStore.Intent.ImportProfileFromUri(uri))
    fun onDismissError() = store.accept(DashboardStore.Intent.DismissError)

    fun interface OnStartVpn { fun invoke(profile: Profile) }
    fun interface OnStopVpn { fun invoke() }
}
