package ru.shapovalov.bedlam.feature.update.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<AssetDto> = emptyList(),
) {
    @Serializable
    internal data class AssetDto(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        @SerialName("size") val size: Long = 0,
    )
}
