package ru.shapovalov.bedlam.feature.update.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
