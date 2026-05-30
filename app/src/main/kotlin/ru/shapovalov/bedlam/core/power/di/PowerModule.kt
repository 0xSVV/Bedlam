package ru.shapovalov.bedlam.core.power.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.power.data.PowerReliabilityRepositoryImpl
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.di.AppScope

interface PowerModule {

    @AppScope
    @Provides
    fun bindPowerReliabilityRepository(
        impl: PowerReliabilityRepositoryImpl,
    ): PowerReliabilityRepository = impl
}
