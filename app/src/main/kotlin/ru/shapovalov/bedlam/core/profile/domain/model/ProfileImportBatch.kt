package ru.shapovalov.bedlam.core.profile.domain.model

data class ProfileImportBatch(
    val imported: List<Profile>,
    val failures: List<ProfileImportFailure>,
) {
    val total: Int get() = imported.size + failures.size
}

sealed interface ProfileImportFailure {
    val position: Int

    data class Duplicate(override val position: Int, val existingName: String) : ProfileImportFailure

    data class Invalid(override val position: Int, val message: String) : ProfileImportFailure
}
