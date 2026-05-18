package ru.shapovalov.bedlam.feature.settings.appselection.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode

@Inject
class AppSelectionComponent(
    storeProviderProvider: () -> AppSelectionStoreProvider,
    @Assisted componentContext: ComponentContext,
    @Assisted private val onBack: OnBack,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeProviderProvider().provide() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state: StateFlow<AppSelectionStore.State> = store.stateFlow(scope)

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    fun onModeSelected(mode: AppFilterMode) = store.accept(AppSelectionStore.Intent.ChangeMode(mode))
    fun onTogglePackage(pkg: String) = store.accept(AppSelectionStore.Intent.TogglePackage(pkg))
    fun onQueryChanged(query: String) = store.accept(AppSelectionStore.Intent.UpdateQuery(query))
    fun onBackPressed() = onBack.invoke()

    fun interface OnBack { fun invoke() }
}
