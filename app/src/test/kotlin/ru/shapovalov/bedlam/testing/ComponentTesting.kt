package ru.shapovalov.bedlam.testing

import android.content.Context
import android.content.ContextWrapper
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.statekeeper.StateKeeper
import com.arkivanov.mvikotlin.core.store.Bootstrapper
import com.arkivanov.mvikotlin.core.store.Executor
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory

inline fun <R> withComponentContext(
    stateKeeper: StateKeeper? = null,
    instanceKeeper: InstanceKeeper? = null,
    block: (LifecycleRegistry, ComponentContext) -> R,
): R {
    val lifecycle = LifecycleRegistry()
    lifecycle.create()
    try {
        return block(lifecycle, DefaultComponentContext(lifecycle, stateKeeper, instanceKeeper))
    } finally {
        lifecycle.destroy()
    }
}

class SkippingBootstrapperStoreFactory(
    private val delegate: StoreFactory = DefaultStoreFactory(),
    private val skips: (Bootstrapper<*>) -> Boolean,
) : StoreFactory {

    override fun <Intent : Any, Action : Any, Message : Any, State : Any, Label : Any> create(
        name: String?,
        autoInit: Boolean,
        initialState: State,
        bootstrapper: Bootstrapper<Action>?,
        executorFactory: () -> Executor<Intent, Action, State, Message, Label>,
        reducer: Reducer<State, Message>,
    ): Store<Intent, State, Label> = delegate.create(
        name = name,
        autoInit = autoInit,
        initialState = initialState,
        bootstrapper = bootstrapper?.takeUnless(skips),
        executorFactory = executorFactory,
        reducer = reducer,
    )
}

class StubAndroidContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}
