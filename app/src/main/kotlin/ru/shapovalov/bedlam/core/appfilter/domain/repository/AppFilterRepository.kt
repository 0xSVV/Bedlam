package ru.shapovalov.bedlam.core.appfilter.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode

interface AppFilterRepository {
    fun observe(): Flow<AppFilter>
    suspend fun get(): AppFilter
    suspend fun setMode(mode: AppFilterMode)
    suspend fun setPackages(packages: Set<String>)
    suspend fun togglePackage(pkg: String)
}
