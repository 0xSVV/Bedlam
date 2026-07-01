package ru.shapovalov.bedlam.core.power

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor

object PowerSettingsLauncher {

    @SuppressLint("BatteryLife")
    fun openBatteryOptimization(context: Context) {
        val packageUri = "package:${context.packageName}".toUri()
        val started = startFirst(
            context = context,
            intents = listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            ),
        )
        if (!started) openAppInfo(context)
    }

    fun openAppInfo(context: Context): Boolean =
        startFirst(
            context = context,
            intents = listOf(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri(),
                ),
            ),
        )

    fun openNotificationSettings(context: Context) {
        startFirst(
            context = context,
            intents = listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri(),
                ),
            ),
        )
    }

    fun openVpnSettings(context: Context) {
        startFirst(
            context = context,
            intents = listOf(
                Intent(Settings.ACTION_VPN_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            ),
        )
    }

    fun openVendorPowerManager(context: Context, vendor: PowerVendor) {
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
        startFirst(context = context, intents = vendor.powerManagerIntents() + fallback)
    }

    private fun PowerVendor.powerManagerIntents(): List<Intent> = when (this) {
        PowerVendor.Xiaomi -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            ),
        )

        PowerVendor.Huawei,
        PowerVendor.Honor -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            ),
        )

        PowerVendor.Vivo -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            ),
        )

        PowerVendor.OnePlus,
        PowerVendor.Oppo,
        PowerVendor.Realme -> listOf(
            Intent().setComponent(
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
            ),
        )

        PowerVendor.Transsion,
        PowerVendor.Asus,
        PowerVendor.Meizu,
        PowerVendor.Lenovo,
        PowerVendor.Samsung,
        PowerVendor.Generic -> emptyList()
    }

    private fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }
}
