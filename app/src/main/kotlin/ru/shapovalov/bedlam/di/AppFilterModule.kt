package ru.shapovalov.bedlam.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.appfilter.data.AppFilterRepositoryImpl
import ru.shapovalov.bedlam.core.appfilter.data.InstalledAppsRepositoryImpl
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.appfilter.domain.repository.InstalledAppsRepository

interface AppFilterModule {

    @AppScope
    @Provides
    fun bindAppFilterRepository(impl: AppFilterRepositoryImpl): AppFilterRepository = impl

    @AppScope
    @Provides
    fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository = impl
}
