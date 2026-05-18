package ru.shapovalov.bedlam.core.appfilter.domain.model

data class AppFilter(
    val mode: AppFilterMode = AppFilterMode.All,
    val packages: Set<String> = emptySet(),
)
