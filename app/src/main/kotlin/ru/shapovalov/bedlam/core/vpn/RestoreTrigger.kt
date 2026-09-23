package ru.shapovalov.bedlam.core.vpn

import android.content.Intent

enum class RestoreTrigger {
    Boot,
    PackageReplaced,
}

fun restoreTriggerFor(action: String?): RestoreTrigger? = when (action) {
    Intent.ACTION_BOOT_COMPLETED -> RestoreTrigger.Boot
    Intent.ACTION_MY_PACKAGE_REPLACED -> RestoreTrigger.PackageReplaced
    else -> null
}
