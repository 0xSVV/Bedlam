package ru.shapovalov.bedlam.feature.update.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.shapovalov.bedlam.di.appComponent

class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        context.appComponent.apkInstaller.onInstallStatus(intent)
    }
}
