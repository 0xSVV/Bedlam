package ru.shapovalov.bedlam.core.profile.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaUri

@Inject
class ImportProfileFromUriUseCase(
    private val repository: ProfileRepository,
    private val hysteriaClient: HysteriaClient,
) {
    suspend operator fun invoke(uri: String, name: String? = null): Result<Profile> = runCatching {
        val config = parseHysteriaUri(uri)
        hysteriaClient.validateConfig(config).getOrThrow()
        val profileName = name?.takeIf { it.isNotBlank() }
            ?: config.name.takeIf { it.isNotBlank() }
            ?: config.server.server
        val profile = Profile.new(profileName, config, System.currentTimeMillis())
        repository.upsert(profile)
        profile
    }
}
