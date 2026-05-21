package ru.shapovalov.bedlam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.arkivanov.decompose.defaultComponentContext
import ru.shapovalov.bedlam.core.vpn.BedlamVpnService
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.bedlam.navigation.RootComponent
import ru.shapovalov.bedlam.ui.theme.BedlamTheme

class MainActivity : ComponentActivity() {

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()

        val rootContext = defaultComponentContext()
        val root = appComponent.rootComponentFactory.create(
            rootContext,
            { _, configJson, profileName ->
                requestVpnPermissionThen { startVpnService(configJson, profileName) }
            },
            { stopVpnService() },
        )

        setContent { BedlamTheme { RootContent(root) } }
    }

    private fun startVpnService(configJson: String, profileName: String) {
        val intent = Intent(this, BedlamVpnService::class.java).apply {
            putExtra(BedlamVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(BedlamVpnService.EXTRA_PROFILE_NAME, profileName)
        }
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, BedlamVpnService::class.java).apply {
            action = BedlamVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestVpnPermissionThen(block: () -> Unit) {
        val intent = VpnService.prepare(this)
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
