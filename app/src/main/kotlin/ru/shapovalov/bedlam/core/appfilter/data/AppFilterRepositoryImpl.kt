package ru.shapovalov.bedlam.core.appfilter.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsDao
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsEntity

@Inject
class AppFilterRepositoryImpl(
    private val dao: AppSettingsDao,
) : AppFilterRepository {

    private val mutex = Mutex()

    override fun observe(): Flow<AppFilter> =
        dao.observe().map { it?.toAppFilter() ?: AppFilter() }

    override suspend fun get(): AppFilter =
        dao.get()?.toAppFilter() ?: AppFilter()

    override suspend fun setMode(mode: AppFilterMode) = mutex.withLock {
        val current = dao.get()
        dao.upsert(
            (current ?: AppSettingsEntity(activeProfileId = null))
                .copy(appFilterMode = mode.toStorage())
        )
    }

    override suspend fun setPackages(packages: Set<String>) = mutex.withLock {
        val current = dao.get()
        dao.upsert(
            (current ?: AppSettingsEntity(activeProfileId = null))
                .copy(appFilterPackages = packages.joinToString(SEPARATOR))
        )
    }

    override suspend fun togglePackage(pkg: String) = mutex.withLock {
        val current = dao.get() ?: AppSettingsEntity(activeProfileId = null)
        val packages = current.appFilterPackages.toPackageSet()
        val updated = if (pkg in packages) packages - pkg else packages + pkg
        dao.upsert(current.copy(appFilterPackages = updated.joinToString(SEPARATOR)))
    }

    private fun AppSettingsEntity.toAppFilter(): AppFilter = AppFilter(
        mode = appFilterMode.toAppFilterMode(),
        packages = appFilterPackages.toPackageSet(),
    )

    companion object {
        private const val SEPARATOR = ","
    }
}

private fun AppFilterMode.toStorage(): String = when (this) {
    AppFilterMode.All -> "ALL"
    AppFilterMode.Allowlist -> "ALLOWLIST"
    AppFilterMode.Blocklist -> "BLOCKLIST"
}

private fun String.toAppFilterMode(): AppFilterMode = when (this) {
    "ALLOWLIST" -> AppFilterMode.Allowlist
    "BLOCKLIST" -> AppFilterMode.Blocklist
    else -> AppFilterMode.All
}

private fun String.toPackageSet(): Set<String> =
    if (isEmpty()) emptySet() else split(',').filter { it.isNotEmpty() }.toSet()
