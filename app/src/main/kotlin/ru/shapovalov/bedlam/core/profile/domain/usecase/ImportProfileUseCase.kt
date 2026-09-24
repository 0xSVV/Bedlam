package ru.shapovalov.bedlam.core.profile.domain.usecase

import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.DuplicateProfileException
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportBatch
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFailure
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaJson
import ru.shapovalov.hysteria.parseHysteriaUri
import ru.shapovalov.hysteria.splitHysteriaLinks

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

    suspend fun importAll(
        text: String,
        format: ProfileImportFormat,
        name: String? = null,
    ): ProfileImportBatch {
        val entries = when (format) {
            ProfileImportFormat.Link -> splitHysteriaLinks(text)
            ProfileImportFormat.Json -> listOf(text)
        }
        val entryName = name.takeIf { entries.size == 1 }
        val imported = mutableListOf<Profile>()
        val failures = mutableListOf<ProfileImportFailure>()
        entries.forEachIndexed { index, entry ->
            invoke(entry, format, entryName)
                .onSuccess { imported += it }
                .onFailure { failures += it.toImportFailure(index + 1) }
        }
        return ProfileImportBatch(imported, failures)
    }

    private fun Throwable.toImportFailure(position: Int): ProfileImportFailure = when (this) {
        is DuplicateProfileException -> ProfileImportFailure.Duplicate(position, existingName)
        else -> ProfileImportFailure.Invalid(position, message.orEmpty())
    }
}
