package ru.shapovalov.bedlam

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.vpn.tile.VpnPermissionActivity
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class VpnPermissionDenialTest {
    @Test fun dashboardDenialShowsExplanationAndDoesNotStartVpn() = deny(fromTile = false)
    @Test fun tileDenialShowsExplanationAndDoesNotStartVpn() = deny(fromTile = true)

    private fun deny(fromTile: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val repository = context.appComponent.profileRepository
        val previous = repository.getActiveId()
        val profile = Profile(
            id = "permission-fixture", name = "Permission fixture",
            config = HysteriaConfig(server = ServerCredentials("127.0.0.1:18443", "fixture"), tls = ru.shapovalov.hysteria.config.TlsOptions()),
            createdAt = 0, updatedAt = 0,
        )
        repository.upsert(profile)
        repository.setActiveId(profile.id)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 37) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
            }
            device.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN ignore")
            assertNotNull(VpnService.prepare(context))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ))
            val connect = device.wait(Until.findObject(By.desc("Connect")), 5_000)
            checkNotNull(connect) { "Home Connect button not found" }
            if (fromTile) {
                context.startActivity(Intent(context, VpnPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else connect.click()
            val cancel = device.wait(Until.findObject(By.text(Pattern.compile("Cancel", Pattern.CASE_INSENSITIVE))), 5_000)
            checkNotNull(cancel) { "Android VPN consent dialog not shown" }
            val feedback = instrumentation.uiAutomation.executeAndWaitForEvent(
                { cancel.click() },
                { event ->
                    event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.text.any { it.toString() == "VPN permission was denied. Allow it to connect." }
                },
                5_000,
            )
            assertTrue("Denying VPN permission must announce visible feedback", feedback.text.isNotEmpty())
            assertTrue(context.appComponent.hysteriaClient.state.value is ConnectionState.Disconnected)
        } finally {
            repository.setActiveId(previous)
            repository.delete(profile.id)
            device.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN allow")
            device.pressHome()
        }
    }
}
