package ru.shapovalov.bedlam

import android.content.Intent
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

@RunWith(AndroidJUnit4::class)
class ProfileLandscapeTest {
    @Test fun toolbarDoesNotCoverScrollingContentAfterRotation() = verifyProfileLayout(withKeyboard = false)
    @Test fun editorStaysAboveKeyboardInPortraitAndLandscape() = verifyProfileLayout(withKeyboard = true)

    private fun verifyProfileLayout(withKeyboard: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val showIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        if (withKeyboard) device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        val repository = context.appComponent.profileRepository
        val previous = repository.getActiveId()
        val profile = Profile(
            id = "landscape-fixture", name = "Landscape fixture",
            config = HysteriaConfig(server = ServerCredentials("127.0.0.1:18443", "fixture"), tls = TlsOptions()),
            createdAt = 0, updatedAt = 0,
        )
        repository.upsert(profile)
        repository.setActiveId(profile.id)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            device.setOrientationNatural()
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            checkNotNull(device.wait(Until.findObject(By.desc("Open profile configuration")), 5_000)) {
                "Home did not show the active fixture profile"
            }.click()
            checkNotNull(device.wait(Until.findObject(By.desc("Edit")), 5_000))
            device.setOrientationLeft()
            device.waitForIdle()
            assertContentAboveToolbar(device)
            device.setOrientationNatural()
            device.waitForIdle()
            assertContentAboveToolbar(device)
            if (withKeyboard) {
                checkNotNull(device.findObject(By.desc("Edit"))).click()
                checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).click()
                assertContentAboveKeyboard(device)
                device.setOrientationLeft()
                device.waitForIdle()
                checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).click()
                assertContentAboveKeyboard(device)
                device.pressBack()
            }
        } finally {
            scenario?.close()
            device.setOrientationNatural()
            if (withKeyboard) {
                if (showIme == "null") device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
                else device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $showIme")
            }
            device.unfreezeRotation()
            repository.setActiveId(previous)
            repository.delete(profile.id)
            device.pressHome()
        }
    }

    private fun assertContentAboveKeyboard(device: UiDevice) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000
        var keyboardTop: Int? = null
        while ((keyboardTop == null) && (SystemClock.uptimeMillis() < deadline)) {
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).single()
                val decor = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                    val location = IntArray(2)
                    decor.getLocationOnScreen(location)
                    keyboardTop = (location[1] + decor.height) - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                }
            }
            if (keyboardTop == null) SystemClock.sleep(100)
        }
        checkNotNull(keyboardTop) { "Soft keyboard was not shown" }
        device.waitForIdle()
        val list = checkNotNull(device.findObject(By.scrollable(true)))
        assertTrue(
            "Scrolling viewport ${list.visibleBounds} extends behind keyboard at $keyboardTop",
            list.visibleBounds.bottom <= keyboardTop,
        )
        val field = checkNotNull(device.findObject(By.clazz("android.widget.EditText").focused(true)))
        assertTrue("Focused input is behind the keyboard", field.visibleBounds.bottom <= keyboardTop)
    }

    private fun assertContentAboveToolbar(device: UiDevice) {
        val edit = checkNotNull(device.wait(Until.findObject(By.desc("Edit")), 5_000))
        val list = checkNotNull(device.findObject(By.scrollable(true)))
        assertTrue(
            "Scrolling viewport ${list.visibleBounds} overlaps toolbar action ${edit.visibleBounds}",
            list.visibleBounds.bottom <= edit.visibleBounds.top,
        )
    }
}
