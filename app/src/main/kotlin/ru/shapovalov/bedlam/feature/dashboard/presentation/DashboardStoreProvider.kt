package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileFromUriUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileIdUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore.Intent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore.Label
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore.State
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

@Inject
class DashboardStoreProvider(
    private val storeFactory: StoreFactory,
    private val getProfiles: GetProfilesUseCase,
    private val observeActiveId: ObserveActiveProfileIdUseCase,
    private val setActiveProfile: SetActiveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val importFromUri: ImportProfileFromUriUseCase,
    private val client: HysteriaClient,
) {

    fun provide(): DashboardStore =
        object : DashboardStore, Store<Intent, State, Label> by storeFactory.create(
            name = "DashboardStore",
            initialState = State(connectionState = client.state.value),
            bootstrapper = BootstrapperImpl(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Msg {
        data class ProfilesLoaded(val profiles: List<Profile>, val activeId: String?) : Msg
        data class ConnectionChanged(val state: ConnectionState, val connectedSinceMillis: Long?) : Msg
        data object ImportStarted : Msg
        data object ImportFinished : Msg
        data class ErrorRaised(val reason: DashboardStore.ErrorReason) : Msg
        data object ErrorDismissed : Msg
    }

    private sealed interface Action {
        data object ObserveProfiles : Action
        data object ObserveConnection : Action
    }

    private inner class BootstrapperImpl : CoroutineBootstrapper<Action>() {
        override fun invoke() {
            dispatch(Action.ObserveProfiles)
            dispatch(Action.ObserveConnection)
        }
    }

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Action, State, Msg, Label>() {

        override fun executeAction(action: Action) {
            when (action) {
                Action.ObserveProfiles -> scope.launch {
                    combine(getProfiles(), observeActiveId()) { profiles, activeId ->
                        Msg.ProfilesLoaded(profiles, activeId)
                    }.collect(::dispatch)
                }

                Action.ObserveConnection -> scope.launch {
                    var connectedSince: Long? = null
                    client.state.collect { state ->
                        connectedSince = when (state) {
                            is ConnectionState.Connected -> connectedSince ?: System.currentTimeMillis()
                            else -> null
                        }
                        dispatch(Msg.ConnectionChanged(state, connectedSince))
                    }
                }
            }
        }

        override fun executeIntent(intent: Intent) {
            when (intent) {
                Intent.ToggleConnection -> toggleConnection()
                is Intent.SelectProfile -> scope.launch { setActiveProfile(intent.id) }
                is Intent.DeleteProfile -> scope.launch { deleteProfile(intent.id) }
                is Intent.ImportProfileFromUri -> handleImport(intent.uri)
                Intent.DismissError -> dispatch(Msg.ErrorDismissed)
            }
        }

        private fun toggleConnection() {
            val s = state()
            when (s.connectionState) {
                is ConnectionState.Connected,
                is ConnectionState.Connecting,
                is ConnectionState.Reconnecting -> publish(Label.RequestStopVpn)

                else -> {
                    val active = s.activeProfile
                    if (active == null) {
                        dispatch(Msg.ErrorRaised(DashboardStore.ErrorReason.NoActiveProfile))
                    } else {
                        publish(Label.RequestStartVpn(active))
                    }
                }
            }
        }

        private fun handleImport(uri: String) {
            val trimmed = uri.trim()
            if (trimmed.isEmpty()) {
                dispatch(Msg.ErrorRaised(DashboardStore.ErrorReason.ClipboardEmpty))
                return
            }
            dispatch(Msg.ImportStarted)
            scope.launch {
                val result = importFromUri(trimmed)
                dispatch(Msg.ImportFinished)
                result
                    .onSuccess { profile ->
                        if (state().activeProfileId == null) {
                            setActiveProfile(profile.id)
                        }
                    }
                    .onFailure { e ->
                        dispatch(Msg.ErrorRaised(DashboardStore.ErrorReason.ImportFailed(e.message)))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<State, Msg> {
        override fun State.reduce(msg: Msg): State = when (msg) {
            is Msg.ProfilesLoaded -> copy(profiles = msg.profiles, activeProfileId = msg.activeId)
            is Msg.ConnectionChanged -> copy(
                connectionState = msg.state,
                connectedSinceMillis = msg.connectedSinceMillis,
            )
            Msg.ImportStarted -> copy(isImporting = true, error = null)
            Msg.ImportFinished -> copy(isImporting = false)
            is Msg.ErrorRaised -> copy(error = msg.reason)
            Msg.ErrorDismissed -> copy(error = null)
        }
    }
}
