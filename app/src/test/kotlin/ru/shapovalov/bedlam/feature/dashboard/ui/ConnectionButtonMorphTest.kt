package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionButtonMorphTest {

    private val resting = RoundedPolygon(numVertices = 4)
    private val loading = listOf(3, 5, 6, 7, 8, 9, 10).map { RoundedPolygon(numVertices = it) }

    private class FakeFrameClock : MonotonicFrameClock {
        var frameCount = 0
            private set

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FrameIntervalMillis)
            frameCount++
            return onFrame(frameCount * FrameIntervalMillis * 1_000_000L)
        }
    }

    private class FakeMotionDurationScale(scale: Float) : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(scale)
    }

    private fun ConnectionButtonMorph.isMidMorphBetweenLoadingShapes() =
        fromShape in loading && toShape in loading && fromShape != toShape && progress > 0f

    private fun TestScope.advanceUntilMidMorphBetweenLoadingShapes(morph: ConnectionButtonMorph) {
        repeat(MaxFramesToReachMidMorph) {
            if (morph.isMidMorphBetweenLoadingShapes()) return
            advanceTimeBy(FrameIntervalMillis)
        }
        assertTrue(
            morph.isMidMorphBetweenLoadingShapes(),
            "no morph between loading shapes within $MaxFramesToReachMidMorph frames",
        )
    }

    @Test
    fun `cancelling the loop stops frame requests`() = runTest {
        val clock = FakeFrameClock()
        val morph = ConnectionButtonMorph(resting, loading, connecting = false)
        val loop = launch(clock + FakeMotionDurationScale(1f)) { morph.animateLoading() }

        advanceTimeBy(2_000)
        loop.cancelAndJoin()
        val framesAtCancel = clock.frameCount
        advanceTimeBy(10_000)

        assertTrue(framesAtCancel > 0)
        assertEquals(framesAtCancel, clock.frameCount)
    }

    @Test
    fun `settling after an interrupted loop restores the resting shape and icon`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = ConnectionButtonMorph(resting, loading, connecting = false)
        val loop = launch(clock + motion) { morph.animateLoading() }

        advanceUntilMidMorphBetweenLoadingShapes(morph)
        loop.cancelAndJoin()
        assertNotEquals(morph.fromShape, morph.toShape)
        assertTrue(morph.progress > 0f)

        launch(clock + motion) { morph.settle() }.join()

        assertEquals(resting, morph.fromShape)
        assertEquals(resting, morph.toShape)
        assertEquals(0f, morph.progress)
        assertTrue(morph.showIcon)
    }
}

private const val FrameIntervalMillis = 16L
private const val MaxFramesToReachMidMorph = 200
