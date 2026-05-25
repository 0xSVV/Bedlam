package ru.shapovalov.bedlam.core.appfilter.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.core.appfilter.domain.repository.InstalledAppsRepository

@Inject
class InstalledAppsRepositoryImpl(
    private val context: Context,
) : InstalledAppsRepository {

    override suspend fun list(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val ownPackage = context.packageName
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .mapNotNull { info ->
                if (info.packageName == ownPackage) return@mapNotNull null
                if (pm.checkPermission(Manifest.permission.INTERNET, info.packageName) != PackageManager.PERMISSION_GRANTED) return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()
    }
}
