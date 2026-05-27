package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.util.componentScope

class AppSelectionComponent(
    componentContext: ComponentContext,
    storeFactory: AppSelectionStoreFactory,
    private val onBack: OnBack,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<AppSelectionStore.State> = store.stateFlow(scope)

    fun onModeSelected(mode: AppFilterMode) =
        store.accept(AppSelectionStore.Intent.ChangeMode(mode))

    fun onTogglePackage(pkg: String) = store.accept(AppSelectionStore.Intent.TogglePackage(pkg))
    fun onQueryChanged(query: String) = store.accept(AppSelectionStore.Intent.UpdateQuery(query))
    fun onBackPressed() = onBack.invoke()

    fun interface OnBack {
        fun invoke()
    }
}
