package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository
import ru.shapovalov.bedlam.feature.update.domain.usecase.DownloadUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.SkipUpdateUseCase

@Inject
class UpdateStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: UpdateRepository,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val skipUpdate: SkipUpdateUseCase,
    private val installer: UpdateInstaller,
) {
    fun create(update: AppUpdate): UpdateStore =
        object : UpdateStore, Store<UpdateStore.Intent, UpdateStore.State, UpdateStore.Label>
        by storeFactory.create(
            name = "UpdateStore",
            initialState = UpdateStore.State(
                update = update,
                currentVersion = repository.installedVersion(),
            ),
            bootstrapper = UpdateBootstrapper(installer),
            executorFactory = { UpdateExecutor(downloadUpdate, skipUpdate, installer) },
            reducer = UpdateReducer,
        ) {}
}
