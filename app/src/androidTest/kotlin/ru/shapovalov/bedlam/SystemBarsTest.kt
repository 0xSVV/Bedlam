package ru.shapovalov.bedlam

import android.content.res.Configuration
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarsTest {
    @Test
    fun iconsFollowLightAndDarkThemeAfterActivityRecreation() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val originalMode = device.executeShellCommand("cmd uimode night").substringAfter(":").trim()
        check(originalMode in setOf("auto", "no", "yes", "custom"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (darkTheme in listOf(false, true)) {
                    device.executeShellCommand("cmd uimode night ${if (darkTheme) "yes" else "no"}")
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    var verified = false
                    while (!verified && (SystemClock.uptimeMillis() < deadline)) {
                        scenario.onActivity { activity ->
                            val nightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                            val expected = if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                            if (nightMode == expected) {
                                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                                assertEquals(!darkTheme, controller.isAppearanceLightStatusBars)
                                assertEquals(!darkTheme, controller.isAppearanceLightNavigationBars)
                                verified = true
                            }
                        }
                        if (!verified) SystemClock.sleep(100)
                    }
                    assertTrue("Activity did not adopt the requested system theme", verified)
                }
            }
        } finally {
            device.executeShellCommand("cmd uimode night $originalMode")
            device.pressHome()
        }
    }
}
