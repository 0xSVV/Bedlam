package ru.shapovalov.bedlam.feature.update.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.appUpdate
import ru.shapovalov.bedlam.testing.reduceAll

class UpdateReducerTest {

    private val idle = UpdateStore.State(update = appUpdate(), currentVersion = "1.5.3")

    private fun phaseAfter(msg: Msg): UpdateStore.State.Phase =
        UpdateReducer.reduceAll(idle, msg).phase

    @Test
    fun `each message sets its phase`() {
        assertEquals(
            UpdateStore.State.Phase.Downloading(downloadedBytes = 10, totalBytes = 1_000),
            phaseAfter(Msg.Downloading(downloadedBytes = 10, totalBytes = 1_000)),
        )
        assertEquals(UpdateStore.State.Phase.Installing, phaseAfter(Msg.Installing))
        assertEquals(
            UpdateStore.State.Phase.NeedsInstallPermission,
            phaseAfter(Msg.NeedsInstallPermission),
        )
        assertEquals(UpdateStore.State.Phase.SignatureMismatch, phaseAfter(Msg.SignatureMismatch))
        assertEquals(UpdateStore.State.Phase.Failed("boom"), phaseAfter(Msg.Failed("boom")))
    }

    @Test
    fun `a phase change keeps the update and the installed version`() {
        val failed = UpdateReducer.reduceAll(idle, Msg.Installing, Msg.Failed("boom"))

        assertEquals(idle.copy(phase = UpdateStore.State.Phase.Failed("boom")), failed)
    }

    @Test
    fun `the last reminder flag survives phase changes`() {
        val failed = UpdateReducer.reduceAll(idle, Msg.LastReminder, Msg.Failed("boom"))

        assertEquals(
            idle.copy(phase = UpdateStore.State.Phase.Failed("boom"), lastReminder = true),
            failed,
        )
    }
}
