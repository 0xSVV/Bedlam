package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.flow.MutableStateFlow
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository

class FakeQuickSettingsTileRepository : QuickSettingsTileRepository {

    override val added = MutableStateFlow(false)

    override suspend fun setAdded(added: Boolean) {
        this.added.value = added
    }
}
