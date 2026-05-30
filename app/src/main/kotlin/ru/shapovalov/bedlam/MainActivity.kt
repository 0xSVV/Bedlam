package ru.shapovalov.bedlam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arkivanov.decompose.defaultComponentContext
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.vpn.VpnServiceLauncher
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.bedlam.di.injected
import ru.shapovalov.bedlam.ui.theme.BedlamTheme

class MainActivity : ComponentActivity() {

    private val vpnServiceLauncher: VpnServiceLauncher by injected { vpnServiceLauncher }
    private var pendingStart: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingStart?.invoke()
        } else {
            Log.w(TAG, "VPN permission denied")
        }
        pendingStart = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Log.w(TAG, "Notification permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()

        val rootContext = defaultComponentContext()
        val root = appComponent.rootComponentFactory.create(
            rootContext,
            { profile ->
                requestVpnPermissionThen { startVpnService(profile) }
            },
            { stopVpnService() },
        )

        setContent { BedlamTheme { RootContent(root) } }
    }

    private fun startVpnService(profile: Profile) {
        vpnServiceLauncher.start(profile)
    }

    private fun stopVpnService() {
        vpnServiceLauncher.stop()
    }

    private fun requestVpnPermissionThen(block: () -> Unit) {
        val intent = vpnServiceLauncher.prepareIntent()
        if (intent != null) {
            pendingStart = block
            vpnPermissionLauncher.launch(intent)
        } else {
            block()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private companion object {
        const val TAG = "Bedlam"
    }
}
