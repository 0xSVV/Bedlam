package ru.shapovalov.bedlam.core.vpn.tile.domain.repository

import kotlinx.coroutines.flow.Flow

interface QuickSettingsTileRepository {
    val added: Flow<Boolean>

    suspend fun setAdded(added: Boolean)
}
