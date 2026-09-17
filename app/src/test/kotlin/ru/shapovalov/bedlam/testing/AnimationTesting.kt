package ru.shapovalov.bedlam.testing

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.delay

const val FrameIntervalMillis = 16L

class FakeFrameClock : MonotonicFrameClock {
    var frameCount = 0
        private set
    var beforeEachFrame: () -> Unit = {}

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(FrameIntervalMillis)
        frameCount++
        beforeEachFrame()
        return onFrame(frameCount * FrameIntervalMillis * 1_000_000L)
    }
}

class FakeMotionDurationScale(private var systemScale: Float) : MotionDurationScale {
    private var observedScale by mutableFloatStateOf(1f)
    private var observingSystemScale = false
    var scaleFactorReads = 0
        private set

    override val scaleFactor: Float
        get() {
            scaleFactorReads++
            if (!observingSystemScale) {
                observedScale = systemScale
                observingSystemScale = true
            }
            return observedScale
        }

    fun changeSystemScale(scale: Float) {
        systemScale = scale
        if (observingSystemScale) observedScale = scale
        Snapshot.sendApplyNotifications()
    }
}
