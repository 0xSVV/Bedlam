package ru.shapovalov.bedlam.core.vpn

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RestoreTriggerTest {

    @Test
    fun `boot restores the tunnel`() {
        assertEquals(RestoreTrigger.Boot, restoreTriggerFor(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `an update of this app restores the tunnel`() {
        assertEquals(
            RestoreTrigger.PackageReplaced,
            restoreTriggerFor(Intent.ACTION_MY_PACKAGE_REPLACED),
        )
    }

    @Test
    fun `other broadcasts restore nothing`() {
        assertNull(restoreTriggerFor(null))
        assertNull(restoreTriggerFor(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(restoreTriggerFor(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(restoreTriggerFor("ru.shapovalov.bedlam.STOP_VPN"))
    }
}
