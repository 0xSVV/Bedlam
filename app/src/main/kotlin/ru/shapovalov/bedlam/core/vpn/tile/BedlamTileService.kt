package ru.shapovalov.bedlam.core.vpn.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.MainActivity
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.bedlam.core.vpn.VpnServiceLauncher
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository
import ru.shapovalov.bedlam.di.injected
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

class BedlamTileService : TileService() {

    private val client: HysteriaClient by injected { hysteriaClient }
    private val profileRepository: ProfileRepository by injected { profileRepository }
    private val vpnServiceLauncher: VpnServiceLauncher by injected { vpnServiceLauncher }
    private val quickSettingsTileRepository: QuickSettingsTileRepository by injected {
        quickSettingsTileRepository
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var clickJob: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        scope.launch { quickSettingsTileRepository.setAdded(true) }
    }

    override fun onTileRemoved() {
        scope.launch { quickSettingsTileRepository.setAdded(false) }
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { quickSettingsTileRepository.setAdded(true) }
        stateJob?.cancel()
        stateJob = scope.launch {
            combine(client.state, profileRepository.observeActiveId()) { state, activeId ->
                state to activeId
            }.collect { (state, activeId) ->
                updateTile(state, activeId)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleClick() }
        } else {
            handleClick()
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        clickJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun handleClick() {
        clickJob?.cancel()
        clickJob = scope.launch {
            val state = client.state.value
            if (state.isTunnelActive()) {
                vpnServiceLauncher.stop()
                updateTile(ConnectionState.Disconnected(), profileRepository.getActiveId())
                return@launch
            }

            val profile = vpnServiceLauncher.activeProfile()
            if (profile == null) {
                openMainActivity()
                return@launch
            }

            if (vpnServiceLauncher.prepareIntent() != null) {
                openVpnPermissionActivity()
            } else {
                vpnServiceLauncher.start(profile)
                updateTile(ConnectionState.Connecting, profile.id)
            }
        }
    }

    private fun updateTile(state: ConnectionState, activeProfileId: String?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tunnel)
        tile.state = when {
            activeProfileId == null -> Tile.STATE_UNAVAILABLE
            state.isTunnelActive() -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = subtitle(state, activeProfileId)
        tile.updateTile()
    }

    private fun subtitle(state: ConnectionState, activeProfileId: String?): String =
        when {
            activeProfileId == null -> getString(R.string.qs_tile_no_profile)
            state is ConnectionState.Connected -> getString(R.string.qs_tile_connected)
            state is ConnectionState.Connecting -> getString(R.string.qs_tile_connecting)
            state is ConnectionState.Reconnecting -> getString(R.string.qs_tile_reconnecting)
            state is ConnectionState.Error -> getString(R.string.qs_tile_error)
            else -> getString(R.string.qs_tile_disconnected)
        }

    private fun openMainActivity() {
        startActivityAndCollapseCompat(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            REQ_OPEN_APP,
        )
    }

    private fun openVpnPermissionActivity() {
        startActivityAndCollapseCompat(
            Intent(this, VpnPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            REQ_VPN_PERMISSION,
        )
    }

    private fun startActivityAndCollapseCompat(intent: Intent, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapsePendingIntent(intent, requestCode)
        } else {
            startActivityAndCollapseIntent(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startActivityAndCollapsePendingIntent(intent: Intent, requestCode: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pendingIntent)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseIntent(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun ConnectionState.isTunnelActive(): Boolean =
        this is ConnectionState.Connected ||
            this is ConnectionState.Connecting ||
            this is ConnectionState.Reconnecting

    private companion object {
        const val REQ_OPEN_APP = 40
        const val REQ_VPN_PERMISSION = 41
    }
}
