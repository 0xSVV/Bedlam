package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository

class FakeAppFilterRepository(
    initial: AppFilter = AppFilter(),
) : AppFilterRepository {

    val filter = MutableStateFlow(initial)

    override fun observe(): Flow<AppFilter> = filter

    override suspend fun get(): AppFilter = filter.value

    override suspend fun setMode(mode: AppFilterMode) =
        filter.update { it.copy(mode = mode) }

    override suspend fun setPackages(packages: Set<String>) =
        filter.update { it.copy(packages = packages) }

    override suspend fun togglePackage(pkg: String) = filter.update { current ->
        val packages = current.packages
        current.copy(packages = if (pkg in packages) packages - pkg else packages + pkg)
    }
}
