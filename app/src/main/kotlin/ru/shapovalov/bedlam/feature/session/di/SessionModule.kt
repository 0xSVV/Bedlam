package ru.shapovalov.bedlam.feature.session.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.bedlam.feature.session.data.SessionInfoRepositoryImpl
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository

interface SessionModule {

    @AppScope
    @Provides
    fun bindSessionInfoRepository(impl: SessionInfoRepositoryImpl): SessionInfoRepository = impl
}
