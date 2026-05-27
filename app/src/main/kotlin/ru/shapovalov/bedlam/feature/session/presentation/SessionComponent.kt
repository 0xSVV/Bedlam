package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.util.componentScope

class SessionComponent(
    componentContext: ComponentContext,
    storeFactory: SessionStoreFactory,
    private val onBack: OnBack,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<SessionStore.State> = store.stateFlow(scope)

    fun onRefresh() = store.accept(SessionStore.Intent.Refresh)
    fun onBackPressed() = onBack.invoke()

    fun interface OnBack { fun invoke() }
}
