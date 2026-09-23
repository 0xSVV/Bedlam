package ru.shapovalov.bedlam.core.vpn

import android.content.Intent

enum class RestoreTrigger(private val occasion: String) {
    Boot("boot"),
    PackageReplaced("an app update");

    fun outcomeOf(result: ReconcileResult): RestoreOutcome? = when (result) {
        ReconcileResult.Unchanged -> null
        ReconcileResult.Restarted -> RestoreOutcome(
            logLine = "Restoring the tunnel after $occasion",
            alertReason = null,
        )

        is ReconcileResult.Failed -> RestoreOutcome(
            logLine = "Could not restore the tunnel after $occasion: ${result.reason}",
            alertReason = result.reason,
        )
    }
}

data class RestoreOutcome(val logLine: String, val alertReason: String?)

fun restoreTriggerFor(action: String?): RestoreTrigger? = when (action) {
    Intent.ACTION_BOOT_COMPLETED -> RestoreTrigger.Boot
    Intent.ACTION_MY_PACKAGE_REPLACED -> RestoreTrigger.PackageReplaced
    else -> null
}
