package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.feature.update.domain.model.DownloadEvent
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import ru.shapovalov.bedlam.feature.update.domain.usecase.DownloadUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.SkipUpdateUseCase
import ru.shapovalov.bedlam.testing.FakeUpdateInstaller
import ru.shapovalov.bedlam.testing.FakeUpdateRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.appUpdate
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.recordLabels
import java.io.File
import java.io.IOException

@ExtendWith(MainDispatcherExtension::class)
class UpdateExecutorTest {

    private val apk = File("bedlam-v9.9.9-universal.apk")

    private fun store(
        repository: FakeUpdateRepository,
        installer: FakeUpdateInstaller = FakeUpdateInstaller(),
        trigger: UpdateTrigger = UpdateTrigger.LaunchCheck,
    ): UpdateStore = UpdateStoreFactory(
        DefaultStoreFactory(),
        repository,
        DownloadUpdateUseCase(repository),
        SkipUpdateUseCase(repository),
        installer,
    ).create(appUpdate(), trigger)

    @Test
    fun `install downloads and hands the file to the installer`() = runTest {
        val repository = FakeUpdateRepository(
            download = flowOf(DownloadEvent.Progress(500, 1_000), DownloadEvent.Completed(apk)),
        )
        val installer = FakeUpdateInstaller()
        store(repository, installer).disposeAfter { store ->
            store.accept(UpdateStore.Intent.Install)

            assertEquals(listOf(apk), installer.installed)
            assertEquals(UpdateStore.State.Phase.Installing, store.state.phase)
        }
    }

    @Test
    fun `a second install while downloading is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var collections = 0
        val repository = FakeUpdateRepository(
            download = flow {
                collections++
                emit(DownloadEvent.Progress(100, 1_000))
                gate.await()
                emit(DownloadEvent.Completed(apk))
            },
        )
        val installer = FakeUpdateInstaller()
        store(repository, installer).disposeAfter { store ->
            store.accept(UpdateStore.Intent.Install)
            store.accept(UpdateStore.Intent.Install)

            assertEquals(1, collections)
            assertEquals(
                UpdateStore.State.Phase.Downloading(downloadedBytes = 100, totalBytes = 1_000),
                store.state.phase,
            )

            gate.complete(Unit)

            assertEquals(listOf(apk), installer.installed)
        }
    }

    @Test
    fun `a download failure shows its message`() = runTest {
        val repository = FakeUpdateRepository(download = flow { throw IOException("boom") })
        store(repository).disposeAfter { store ->
            store.accept(UpdateStore.Intent.Install)

            assertEquals(UpdateStore.State.Phase.Failed("boom"), store.state.phase)
        }
    }

    @Test
    fun `skip records the version and dismisses`() = runTest {
        val repository = FakeUpdateRepository()
        store(repository).disposeAfter { store ->
            val labels = store.recordLabels()

            store.accept(UpdateStore.Intent.Skip)

            assertEquals(listOf("9.9.9"), repository.skipped)
            assertEquals(listOf(UpdateStore.Label.Dismiss), labels)
        }
    }

    @Test
    fun `skip after a manual check dismisses without counting a skip`() = runTest {
        val repository = FakeUpdateRepository()
        store(repository, trigger = UpdateTrigger.ManualCheck).disposeAfter { store ->
            val labels = store.recordLabels()

            store.accept(UpdateStore.Intent.Skip)

            assertEquals(emptyList<String>(), repository.skipped)
            assertEquals(listOf(UpdateStore.Label.Dismiss), labels)
        }
    }

    @Test
    fun `installer statuses map to phases`() = runTest {
        val installer = FakeUpdateInstaller()
        installer.status.value = InstallStatus.Failed("stale")
        store(FakeUpdateRepository(), installer).disposeAfter { store ->
            assertEquals(1, installer.resetCount)
            assertEquals(UpdateStore.State.Phase.Idle, store.state.phase)

            installer.status.value = InstallStatus.InProgress
            assertEquals(UpdateStore.State.Phase.Installing, store.state.phase)

            installer.status.value = InstallStatus.NeedsInstallPermission
            assertEquals(UpdateStore.State.Phase.NeedsInstallPermission, store.state.phase)

            installer.status.value = InstallStatus.SignatureMismatch
            assertEquals(UpdateStore.State.Phase.SignatureMismatch, store.state.phase)

            installer.status.value = InstallStatus.Failed("no space")
            assertEquals(UpdateStore.State.Phase.Failed("no space"), store.state.phase)
        }
    }
}
