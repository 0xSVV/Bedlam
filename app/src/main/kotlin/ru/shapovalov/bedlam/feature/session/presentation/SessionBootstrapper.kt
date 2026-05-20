package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper

internal sealed interface Action {
    data object Load : Action
}

internal class SessionBootstrapper : CoroutineBootstrapper<Action>() {
    override fun invoke() {
        dispatch(Action.Load)
    }
}
