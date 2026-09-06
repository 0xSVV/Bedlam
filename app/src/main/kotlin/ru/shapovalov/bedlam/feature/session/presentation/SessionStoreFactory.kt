package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository
import ru.shapovalov.hysteria.api.HysteriaClient

@Inject
class SessionStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: SessionInfoRepository,
    private val client: HysteriaClient,
) {
    fun create(): SessionStore =
        object : SessionStore, Store<SessionStore.Intent, SessionStore.State, Nothing>
        by storeFactory.create(
            name = "SessionStore",
            initialState = SessionStore.State(),
            bootstrapper = SessionBootstrapper(client),
            executorFactory = { SessionExecutor(repository) },
            reducer = SessionReducer,
        ) {}
}
