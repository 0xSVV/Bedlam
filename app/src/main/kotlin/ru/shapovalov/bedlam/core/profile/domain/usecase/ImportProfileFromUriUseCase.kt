package ru.shapovalov.bedlam.core.profile.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaConfig

@Inject
class ImportProfileFromUriUseCase(
    private val repository: ProfileRepository,
    private val hysteriaClient: HysteriaClient,
) {
    suspend operator fun invoke(uri: String, name: String? = null): Result<Profile> = runCatching {
        val parsed = parseHysteriaConfig(uri)
        hysteriaClient.validateConfig(parsed.config).getOrThrow()
        val profileName = name?.takeIf { it.isNotBlank() }
            ?: parsed.name.takeIf { it.isNotBlank() }
            ?: parsed.config.server.address
        val profile = Profile.new(profileName, parsed.config, System.currentTimeMillis())
        repository.upsert(profile)
        profile
    }
}
