package ru.shapovalov.bedlam.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.di.appComponent

class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val reconcile = context.applicationContext.appComponent.reconcileConnectionState
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                reconcile()
            } catch (e: Exception) {
                Log.w(TAG, "Boot restore failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootRestore"
    }
}
