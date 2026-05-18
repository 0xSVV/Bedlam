package ru.shapovalov.bedlam.core.profile.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.profile.data.ProfileRepositoryImpl
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.bedlam.di.AppScope

interface ProfileModule {

    @AppScope
    @Provides
    fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository = impl
}
