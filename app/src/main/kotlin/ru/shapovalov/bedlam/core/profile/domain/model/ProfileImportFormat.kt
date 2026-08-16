package ru.shapovalov.bedlam.core.profile.domain.model

enum class ProfileImportFormat { Link, Json }

fun detectProfileImportFormat(text: String): ProfileImportFormat =
    if (text.trimStart().startsWith("{")) ProfileImportFormat.Json else ProfileImportFormat.Link
