package ru.shapovalov.bedlam.core.vpn

import android.app.Service
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectHapticTest {

    @Test
    fun `a start the user asked for vibrates on connect`() {
        assertTrue(vibratesOnConnect(userInitiated = true, startFlags = 0))
    }

    @Test
    fun `a boot, update or Always-on start stays silent`() {
        assertFalse(vibratesOnConnect(userInitiated = false, startFlags = 0))
    }

    @Test
    fun `a start Android redelivers after the process died stays silent`() {
        assertFalse(
            vibratesOnConnect(userInitiated = true, startFlags = Service.START_FLAG_REDELIVERY)
        )
        assertFalse(vibratesOnConnect(userInitiated = true, startFlags = Service.START_FLAG_RETRY))
    }
}
