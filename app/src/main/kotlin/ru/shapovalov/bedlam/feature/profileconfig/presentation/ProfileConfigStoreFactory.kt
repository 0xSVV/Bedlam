package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.hysteria.api.HysteriaClient

@Inject
class ProfileConfigStoreFactory(
    private val storeFactory: StoreFactory,
    private val observeProfile: ObserveProfileUseCase,
    private val saveProfile: SaveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val client: HysteriaClient,
) {
    fun create(profileId: String): ProfileConfigStore =
        object : ProfileConfigStore, Store<ProfileConfigStore.Intent, ProfileConfigStore.State, Nothing>
        by storeFactory.create(
            name = "ProfileConfigStore",
            initialState = ProfileConfigStore.State(profileId = profileId),
            bootstrapper = ProfileConfigBootstrapper(profileId, observeProfile),
            executorFactory = { ProfileConfigExecutor(saveProfile, deleteProfile, client) },
            reducer = ProfileConfigReducer,
        ) {}
}
