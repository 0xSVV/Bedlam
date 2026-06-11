package ru.shapovalov.bedlam.feature.update.domain.usecase

import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.model.DownloadEvent
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository

@Inject
class DownloadUpdateUseCase(private val repository: UpdateRepository) {
    operator fun invoke(update: AppUpdate): Flow<DownloadEvent> = repository.downloadApk(update)
}
