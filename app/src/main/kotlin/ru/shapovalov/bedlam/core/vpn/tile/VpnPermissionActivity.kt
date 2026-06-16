package ru.shapovalov.bedlam.core.vpn.tile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.MainActivity
import ru.shapovalov.bedlam.core.vpn.StartActiveProfileResult
import ru.shapovalov.bedlam.core.vpn.VpnServiceLauncher
import ru.shapovalov.bedlam.di.injected

class VpnPermissionActivity : ComponentActivity() {

    private val vpnServiceLauncher: VpnServiceLauncher by injected { vpnServiceLauncher }
    private var requested = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startActiveProfileAndFinish()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requested = savedInstanceState?.getBoolean(KEY_REQUESTED) ?: false
        if (!requested) requestVpnPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_REQUESTED, requested)
        super.onSaveInstanceState(outState)
    }

    private fun requestVpnPermission() {
        requested = true
        val intent = vpnServiceLauncher.prepareIntent()
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startActiveProfileAndFinish()
        }
    }

    private fun startActiveProfileAndFinish() {
        lifecycleScope.launch {
            when (vpnServiceLauncher.startActiveProfile()) {
                StartActiveProfileResult.NoActiveProfile -> openMainActivity()
                StartActiveProfileResult.Started -> Unit
            }
            finish()
        }
    }

    private fun openMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }

    private companion object {
        const val KEY_REQUESTED = "requested"
    }
}
