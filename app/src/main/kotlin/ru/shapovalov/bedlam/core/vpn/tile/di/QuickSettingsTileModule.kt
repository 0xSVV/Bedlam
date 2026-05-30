package ru.shapovalov.bedlam.core.vpn.tile.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.vpn.tile.data.QuickSettingsTileRepositoryImpl
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository
import ru.shapovalov.bedlam.di.AppScope

interface QuickSettingsTileModule {

    @AppScope
    @Provides
    fun bindQuickSettingsTileRepository(
        impl: QuickSettingsTileRepositoryImpl,
    ): QuickSettingsTileRepository = impl
}
