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
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.bedlam.core.vpn.VpnServiceLauncher
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.bedlam.di.injected
import ru.shapovalov.bedlam.ui.theme.BedlamTheme

class MainActivity : ComponentActivity() {

    private val vpnServiceLauncher: VpnServiceLauncher by injected { vpnServiceLauncher }
    private val profileRepository: ProfileRepository by injected { profileRepository }
    private var pendingStartProfileId: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val profileId = pendingStartProfileId
        pendingStartProfileId = null
        if (result.resultCode != RESULT_OK) {
            Log.w(TAG, "VPN permission denied")
        } else if (profileId != null) {
            lifecycleScope.launch {
                profileRepository.get(profileId)?.let { startVpnService(it) }
            }
        }
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
        pendingStartProfileId = savedInstanceState?.getString(KEY_PENDING_PROFILE_ID)
        ensureNotificationPermission()

        val rootContext = defaultComponentContext()
        val root = appComponent.rootComponentFactory.create(
            rootContext,
            { profile -> requestVpnPermissionThen(profile) },
            { stopVpnService() },
        )

        setContent { BedlamTheme { RootContent(root) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingStartProfileId?.let { outState.putString(KEY_PENDING_PROFILE_ID, it) }
    }

    private fun startVpnService(profile: Profile) {
        vpnServiceLauncher.start(profile)
    }

    private fun stopVpnService() {
        vpnServiceLauncher.stop()
    }

    private fun requestVpnPermissionThen(profile: Profile) {
        val intent = vpnServiceLauncher.prepareIntent()
        if (intent != null) {
            pendingStartProfileId = profile.id
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService(profile)
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
        const val KEY_PENDING_PROFILE_ID = "pending_start_profile_id"
    }
}
