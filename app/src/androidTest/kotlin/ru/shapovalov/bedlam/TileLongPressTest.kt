package ru.shapovalov.bedlam

import android.content.Intent
import android.service.quicksettings.TileService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.ConnectionState

@RunWith(AndroidJUnit4::class)
class TileLongPressTest {
    @Test
    fun actualTileLongPressOpensBedlamWithoutStartingVpn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(context.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(context.packageManager)?.className)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val original = device.executeShellCommand("settings get secure sysui_qs_tiles").trim()
        val component = "${context.packageName}/.core.vpn.tile.BedlamTileService"
        val alreadyAdded = original.contains(component)
        try {
            device.executeShellCommand("cmd statusbar add-tile $component")
            device.pressHome()
            device.openQuickSettings()
            device.waitForIdle()
            val tileSelector = By.pkg("com.android.systemui").descContains("Bedlam")
            var tile = device.wait(Until.findObject(tileSelector), 2_000)
            repeat(3) {
                if (tile == null) {
                    device.findObject(By.res("com.android.systemui", "qs_pager"))
                        ?.swipe(androidx.test.uiautomator.Direction.LEFT, 0.8f)
                    device.waitForIdle()
                    tile = device.wait(Until.findObject(tileSelector), 2_000)
                }
            }
            checkNotNull(tile) { "Bedlam tile was not added to the emulator" }.longClick()
            assertTrue("Long press should open Bedlam", device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000))
            assertTrue(context.appComponent.hysteriaClient.state.value is ConnectionState.Disconnected)
        } finally {
            if (!alreadyAdded) device.executeShellCommand("cmd statusbar remove-tile $component")
            device.pressHome()
        }
    }
}
