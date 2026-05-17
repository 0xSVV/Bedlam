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

@Inject
class DashboardComponentImpl(
    storeProviderProvider: () -> DashboardStoreProvider,
    @Assisted componentContext: ComponentContext,
    @Assisted private val onStartVpn: DashboardComponent.OnStartVpn,
    @Assisted private val onStopVpn: DashboardComponent.OnStopVpn,
) : DashboardComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeProviderProvider().provide() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val state: StateFlow<DashboardStore.State> = store.stateFlow(scope)

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

    override fun onToggleConnection() = store.accept(DashboardStore.Intent.ToggleConnection)
    override fun onSelectProfile(id: String) = store.accept(DashboardStore.Intent.SelectProfile(id))
    override fun onDeleteProfile(id: String) = store.accept(DashboardStore.Intent.DeleteProfile(id))
    override fun onImportFromClipboard(uri: String) = store.accept(DashboardStore.Intent.ImportProfileFromUri(uri))
    override fun onDismissError() = store.accept(DashboardStore.Intent.DismissError)
}
