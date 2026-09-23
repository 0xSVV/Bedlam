package ru.shapovalov.bedlam.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.log.AppLog
import ru.shapovalov.bedlam.core.vpn.notification.ConnectionAlerts
import ru.shapovalov.bedlam.di.appComponent

class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = restoreTriggerFor(intent.action) ?: return

        val appContext = context.applicationContext
        val component = appContext.appComponent
        val reconcile = component.reconcileConnectionState
        val appLog = component.appLog
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val outcome = trigger.outcomeOf(reconcile())
                if (outcome != null) {
                    if (outcome.alertReason == null) {
                        appLog.info(AppLog.SOURCE_VPN, outcome.logLine)
                    } else {
                        appLog.error(AppLog.SOURCE_VPN, outcome.logLine)
                        ConnectionAlerts(appContext).postStopped(outcome.alertReason)
                    }
                }
            } catch (e: Exception) {
                val line = trigger.crashLogLine(e)
                Log.w(TAG, line, e)
                appLog.error(AppLog.SOURCE_VPN, line)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootRestore"
    }
}
