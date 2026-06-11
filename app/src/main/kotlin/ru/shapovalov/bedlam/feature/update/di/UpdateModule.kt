package ru.shapovalov.bedlam.feature.update.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.bedlam.feature.update.data.ApkInstallerImpl
import ru.shapovalov.bedlam.feature.update.data.UpdateRepositoryImpl
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository

interface UpdateModule {

    @AppScope
    @Provides
    fun bindUpdateRepository(impl: UpdateRepositoryImpl): UpdateRepository = impl

    @Provides
    fun bindUpdateInstaller(impl: ApkInstallerImpl): UpdateInstaller = impl
}
