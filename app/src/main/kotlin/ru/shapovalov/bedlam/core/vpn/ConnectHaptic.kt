package ru.shapovalov.bedlam.core.vpn

import android.app.Service
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal fun Context.vibrateConnected() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    runCatching {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }
}

internal fun vibratesOnConnect(userInitiated: Boolean, startFlags: Int): Boolean =
    userInitiated && (startFlags and AUTOMATIC_START_FLAGS) == 0

private const val AUTOMATIC_START_FLAGS = Service.START_FLAG_REDELIVERY or Service.START_FLAG_RETRY
