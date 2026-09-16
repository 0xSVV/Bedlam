package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionButtonMorphTest {

    private val resting = RoundedPolygon(numVertices = 4)
    private val loading = listOf(3, 5, 6, 7, 8, 9, 10).map { RoundedPolygon(numVertices = it) }

    private class FakeFrameClock : MonotonicFrameClock {
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

    private class FakeMotionDurationScale(private var systemScale: Float) : MotionDurationScale {
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
    fun `loading keeps morphing past the former five cycle limit`() = runTest {
        val clock = FakeFrameClock()
        val morph = ConnectionButtonMorph(resting, loading, connecting = false)
        var lastShape = morph.fromShape
        var loadingShapesReached = 0
        clock.beforeEachFrame = {
            if (morph.fromShape != lastShape) {
                lastShape = morph.fromShape
                if (lastShape in loading) loadingShapesReached++
            }
        }
        val loop = backgroundScope.launch(clock + FakeMotionDurationScale(1f)) {
            morph.animateLoading()
        }

        advanceTimeBy(60_000)

        assertTrue(loadingShapesReached > 5 * loading.size, "morphs: $loadingShapesReached")
        assertTrue(loop.isActive)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `a morph created while connecting starts on the first loading shape`() {
        val connectingMorph = ConnectionButtonMorph(resting, loading, connecting = true)
        assertEquals(loading.first(), connectingMorph.fromShape)
        assertEquals(loading.first(), connectingMorph.toShape)
        assertFalse(connectingMorph.showIcon)

        val idleMorph = ConnectionButtonMorph(resting, loading, connecting = false)
        assertEquals(resting, idleMorph.fromShape)
        assertEquals(resting, idleMorph.toShape)
        assertTrue(idleMorph.showIcon)
    }

    @Test
    fun `a morph created while connecting with animations off holds its shape without a frame`() = runTest {
        val clock = FakeFrameClock()
        val morph = ConnectionButtonMorph(resting, loading, connecting = true)
        val loop = backgroundScope.launch(clock + FakeMotionDurationScale(0f)) {
            morph.animateLoading()
        }

        advanceTimeBy(60_000)

        assertTrue(loop.isActive)
        assertEquals(0, clock.frameCount, "frames: ${clock.frameCount}")
        assertEquals(loading.first(), morph.fromShape)
        assertEquals(loading.first(), morph.toShape)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `a morph needs at least two distinct loading shapes`() {
        val onlyShape = loading.first()
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionButtonMorph(resting, listOf(onlyShape), connecting = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionButtonMorph(resting, listOf(onlyShape, onlyShape), connecting = false)
        }
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

    @Test
    fun `animations off hold a loading shape without requesting frames or polling the scale`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(0f)
        val morph = ConnectionButtonMorph(resting, loading, connecting = false)
        val loop = backgroundScope.launch(clock + motion) { morph.animateLoading() }

        advanceTimeBy(1_000)
        val scaleFactorReadsWhileHolding = motion.scaleFactorReads
        advanceTimeBy(60_000)

        assertTrue(loop.isActive)
        assertTrue(clock.frameCount <= 2, "frames: ${clock.frameCount}")
        val scaleFactorReadsSinceHolding = motion.scaleFactorReads - scaleFactorReadsWhileHolding
        assertEquals(0, scaleFactorReadsSinceHolding, "scale reads: $scaleFactorReadsSinceHolding")
        assertEquals(loading.first(), morph.fromShape)
        assertEquals(loading.first(), morph.toShape)
        assertEquals(0f, morph.progress)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `turning animations off mid morph holds the next loading shape`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = ConnectionButtonMorph(resting, loading, connecting = false)
        val loop = backgroundScope.launch(clock + motion) { morph.animateLoading() }

        advanceUntilMidMorphBetweenLoadingShapes(morph)
        val targetShape = morph.toShape
        val framesAtSwitch = clock.frameCount
        motion.changeSystemScale(0f)
        advanceTimeBy(60_000)

        assertTrue(loop.isActive)
        val framesAfterSwitch = clock.frameCount - framesAtSwitch
        assertTrue(framesAfterSwitch <= 2, "frames: $framesAfterSwitch")
        assertEquals(targetShape, morph.fromShape)
        assertEquals(targetShape, morph.toShape)
        assertEquals(0f, morph.progress)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `re-enabled animations resume the loop`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(0f)
        val morph = ConnectionButtonMorph(resting, loading, connecting = false)
        val loop = backgroundScope.launch(clock + motion) { morph.animateLoading() }

        advanceTimeBy(1_000)
        val framesWhileOff = clock.frameCount
        motion.changeSystemScale(1f)
        advanceTimeBy(5_000)

        assertTrue(loop.isActive)
        val framesAfterEnabling = clock.frameCount - framesWhileOff
        assertTrue(framesAfterEnabling > 100, "frames: $framesAfterEnabling")
        assertFalse(morph.showIcon)
    }

    @Test
    fun `settling with animations off finishes within a few frames`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(0f)
        val morph = ConnectionButtonMorph(resting, loading, connecting = true)
        val loop = launch(clock + motion) { morph.animateLoading() }

        advanceTimeBy(1_000)
        assertTrue(loop.isActive)
        assertEquals(loading.first(), morph.fromShape)
        assertFalse(morph.showIcon)
        loop.cancelAndJoin()
        val framesBeforeSettle = clock.frameCount
        launch(clock + motion) { morph.settle() }.join()

        val settleFrames = clock.frameCount - framesBeforeSettle
        assertTrue(settleFrames <= 2, "frames: $settleFrames")
        assertEquals(resting, morph.fromShape)
        assertEquals(resting, morph.toShape)
        assertEquals(0f, morph.progress)
        assertTrue(morph.showIcon)
    }
}

private const val FrameIntervalMillis = 16L
private const val MaxFramesToReachMidMorph = 200
