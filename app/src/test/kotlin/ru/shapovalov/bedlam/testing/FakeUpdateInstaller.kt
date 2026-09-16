package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.flow.MutableStateFlow
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller
import java.io.File

class FakeUpdateInstaller : UpdateInstaller {

    override val status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val installed = mutableListOf<File>()
    var resetCount = 0
        private set

    override suspend fun install(apk: File) {
        installed += apk
    }

    override fun reset() {
        resetCount++
        status.value = InstallStatus.Idle
    }
}
