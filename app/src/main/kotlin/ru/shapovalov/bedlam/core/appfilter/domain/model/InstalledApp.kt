package ru.shapovalov.bedlam.core.appfilter.domain.model

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)
