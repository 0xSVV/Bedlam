package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class WakeLockHolder(
    context: Context,
    private val scope: CoroutineScope,
    private val tag: String = "bedlam:vpn",
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val refreshMs: Long = DEFAULT_REFRESH_MS,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var refreshJob: Job? = null

    fun acquire() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(refreshMs)
                wakeLock?.acquire(timeoutMs) ?: return@launch
            }
        }
    }

    fun release() {
        refreshJob?.cancel()
        refreshJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private val DEFAULT_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1)
        private val DEFAULT_REFRESH_MS = TimeUnit.MINUTES.toMillis(50)
    }
}
