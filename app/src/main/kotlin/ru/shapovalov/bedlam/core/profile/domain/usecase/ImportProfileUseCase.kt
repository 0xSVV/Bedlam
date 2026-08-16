package ru.shapovalov.bedlam.core.profile.domain.usecase

import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.DuplicateProfileException
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaJson
import ru.shapovalov.hysteria.parseHysteriaUri

@Inject
class ImportProfileUseCase(
    private val repository: ProfileRepository,
    private val hysteriaClient: HysteriaClient,
) {
    suspend operator fun invoke(
        text: String,
        format: ProfileImportFormat,
        name: String? = null,
    ): Result<Profile> = runCatching {
        val parsed = when (format) {
            ProfileImportFormat.Link -> parseHysteriaUri(text)
            ProfileImportFormat.Json -> parseHysteriaJson(text)
        }
        hysteriaClient.validateConfig(parsed.config).getOrThrow()
        repository.observeAll().first()
            .firstOrNull { it.config == parsed.config }
            ?.let { throw DuplicateProfileException(it.name) }
        val profileName = name?.takeIf { it.isNotBlank() }
            ?: parsed.name.takeIf { it.isNotBlank() }
            ?: parsed.config.server.address
        val profile = Profile.new(profileName, parsed.config, System.currentTimeMillis())
        repository.upsert(profile)
        profile
    }
}
