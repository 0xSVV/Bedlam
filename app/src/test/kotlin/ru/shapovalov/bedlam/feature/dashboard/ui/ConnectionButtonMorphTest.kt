package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
import ru.shapovalov.bedlam.testing.FakeFrameClock
import ru.shapovalov.bedlam.testing.FakeMotionDurationScale
import ru.shapovalov.bedlam.testing.FrameIntervalMillis

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionButtonMorphTest {

    private val resting = RoundedPolygon(numVertices = 4)
    private val loading = listOf(3, 5, 6, 7, 8, 9, 10).map { RoundedPolygon(numVertices = it) }
    private val confirm = RoundedPolygon(numVertices = 12)
    private val cancel = RoundedPolygon(numVertices = 11)

    private fun morph(connecting: Boolean = false, confirming: Boolean = false) =
        ConnectionButtonMorph(resting, loading, confirm, cancel, connecting, confirming)

    private fun ConnectionButtonMorph.isMidMorphBetweenLoadingShapes() =
        button.fromShape in loading &&
            button.toShape in loading &&
            button.fromShape != button.toShape &&
            button.progress > 0f

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
        val morph = morph()
        var lastShape = morph.button.fromShape
        var loadingShapesReached = 0
        clock.beforeEachFrame = {
            if (morph.button.fromShape != lastShape) {
                lastShape = morph.button.fromShape
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
        val connectingMorph = morph(connecting = true)
        assertEquals(loading.first(), connectingMorph.button.fromShape)
        assertEquals(loading.first(), connectingMorph.button.toShape)
        assertFalse(connectingMorph.showIcon)
        assertFalse(connectingMorph.isSplit)
        assertEquals(0f, connectingMorph.split)

        val idleMorph = morph()
        assertEquals(resting, idleMorph.button.fromShape)
        assertEquals(resting, idleMorph.button.toShape)
        assertTrue(idleMorph.showIcon)
        assertFalse(idleMorph.isSplit)
    }

    @Test
    fun `a morph created while confirming starts split`() {
        val morph = morph(connecting = true, confirming = true)
        assertTrue(morph.isSplit)
        assertEquals(1f, morph.split)
        assertEquals(confirm, morph.button.fromShape)
        assertEquals(confirm, morph.button.toShape)
        assertEquals(cancel, morph.cancelButton.fromShape)
        assertEquals(cancel, morph.cancelButton.toShape)
        assertTrue(morph.showIcon)
    }

    @Test
    fun `a morph created while connecting with animations off holds its shape without a frame`() = runTest {
        val clock = FakeFrameClock()
        val morph = morph(connecting = true)
        val loop = backgroundScope.launch(clock + FakeMotionDurationScale(0f)) {
            morph.animateLoading()
        }

        advanceTimeBy(60_000)

        assertTrue(loop.isActive)
        assertEquals(0, clock.frameCount, "frames: ${clock.frameCount}")
        assertEquals(loading.first(), morph.button.fromShape)
        assertEquals(loading.first(), morph.button.toShape)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `a morph needs at least two distinct loading shapes`() {
        val onlyShape = loading.first()
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionButtonMorph(resting, listOf(onlyShape), confirm, cancel, connecting = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionButtonMorph(
                resting,
                listOf(onlyShape, onlyShape),
                confirm,
                cancel,
                connecting = false,
            )
        }
    }

    @Test
    fun `cancelling the loop stops frame requests`() = runTest {
        val clock = FakeFrameClock()
        val morph = morph()
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
        val morph = morph()
        val loop = launch(clock + motion) { morph.animateLoading() }

        advanceUntilMidMorphBetweenLoadingShapes(morph)
        loop.cancelAndJoin()
        assertNotEquals(morph.button.fromShape, morph.button.toShape)
        assertTrue(morph.button.progress > 0f)

        launch(clock + motion) { morph.settle() }.join()

        assertEquals(resting, morph.button.fromShape)
        assertEquals(resting, morph.button.toShape)
        assertEquals(0f, morph.button.progress)
        assertTrue(morph.showIcon)
    }

    @Test
    fun `splitting slides the cancel button out of the resting shape`() = runTest {
        val clock = FakeFrameClock()
        val morph = morph()
        val firstFrame = mutableListOf<Pair<Float, RoundedPolygon>>()
        clock.beforeEachFrame = {
            if (firstFrame.isEmpty()) firstFrame += morph.split to morph.cancelButton.fromShape
        }

        launch(clock + FakeMotionDurationScale(1f)) { morph.split() }.join()

        assertEquals(listOf(0f to resting), firstFrame)
        assertTrue(morph.isSplit)
        assertEquals(1f, morph.split)
        assertEquals(confirm, morph.button.fromShape)
        assertEquals(confirm, morph.button.toShape)
        assertEquals(0f, morph.button.progress)
        assertEquals(cancel, morph.cancelButton.fromShape)
        assertEquals(cancel, morph.cancelButton.toShape)
        assertEquals(0f, morph.cancelButton.progress)
        assertTrue(morph.showIcon)
    }

    @Test
    fun `splitting while loading reverses the interrupted morph before the buttons part`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = morph()
        val loop = launch(clock + motion) { morph.animateLoading() }
        advanceUntilMidMorphBetweenLoadingShapes(morph)
        loop.cancelAndJoin()
        val interruptedShape = morph.button.fromShape
        var cancelShapeWhenSplit: RoundedPolygon? = null
        var buttonShapeWhenSplit: RoundedPolygon? = null
        var buttonProgressWhenSplit = -1f
        clock.beforeEachFrame = {
            if (morph.isSplit && cancelShapeWhenSplit == null) {
                cancelShapeWhenSplit = morph.cancelButton.fromShape
                buttonShapeWhenSplit = morph.button.fromShape
                buttonProgressWhenSplit = morph.button.progress
            }
        }

        launch(clock + motion) { morph.split() }.join()

        assertEquals(interruptedShape, cancelShapeWhenSplit)
        assertEquals(interruptedShape, buttonShapeWhenSplit)
        assertEquals(0f, buttonProgressWhenSplit)
        assertTrue(morph.showIcon)
        assertEquals(1f, morph.split)
        assertEquals(confirm, morph.button.fromShape)
        assertEquals(cancel, morph.cancelButton.fromShape)
    }

    @Test
    fun `settling after a split merges the buttons back into the resting shape`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = morph()
        launch(clock + motion) { morph.split() }.join()

        launch(clock + motion) { morph.settle() }.join()

        assertFalse(morph.isSplit)
        assertEquals(0f, morph.split)
        assertEquals(resting, morph.button.fromShape)
        assertEquals(resting, morph.button.toShape)
        assertEquals(0f, morph.button.progress)
        assertEquals(resting, morph.cancelButton.fromShape)
        assertEquals(resting, morph.cancelButton.toShape)
        assertTrue(morph.showIcon)
    }

    @Test
    fun `splitting again during a merge keeps the cancel button out`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = morph()
        launch(clock + motion) { morph.split() }.join()
        val merge = launch(clock + motion) { morph.settle() }
        advanceTimeBy(FrameIntervalMillis * 6)
        merge.cancelAndJoin()
        val splitAtInterruption = morph.split
        assertTrue(splitAtInterruption > 0f && splitAtInterruption < 1f, "split: $splitAtInterruption")
        assertTrue(morph.isSplit)
        var minSplit = splitAtInterruption
        clock.beforeEachFrame = { minSplit = minOf(minSplit, morph.split) }

        launch(clock + motion) { morph.split() }.join()

        assertTrue(minSplit > 0f, "split: $minSplit")
        assertTrue(morph.isSplit)
        assertEquals(1f, morph.split)
        assertEquals(confirm, morph.button.fromShape)
        assertEquals(cancel, morph.cancelButton.fromShape)
    }

    @Test
    fun `loading after an interrupted merge finishes merging first`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = morph()
        launch(clock + motion) { morph.split() }.join()
        val merge = launch(clock + motion) { morph.settle() }
        advanceTimeBy(FrameIntervalMillis * 6)
        merge.cancelAndJoin()
        assertTrue(morph.isSplit)

        val loop = backgroundScope.launch(clock + motion) { morph.animateLoading() }
        advanceTimeBy(5_000)

        assertTrue(loop.isActive)
        assertFalse(morph.isSplit)
        assertEquals(0f, morph.split)
        assertEquals(resting, morph.cancelButton.fromShape)
        assertTrue(morph.button.fromShape in loading)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `splitting with animations off finishes within a few frames`() = runTest {
        val clock = FakeFrameClock()
        val morph = morph()

        launch(clock + FakeMotionDurationScale(0f)) { morph.split() }.join()

        assertTrue(clock.frameCount <= 6, "frames: ${clock.frameCount}")
        assertTrue(morph.isSplit)
        assertEquals(1f, morph.split)
        assertEquals(confirm, morph.button.fromShape)
        assertEquals(cancel, morph.cancelButton.fromShape)
    }

    @Test
    fun `animations off hold a loading shape without requesting frames or polling the scale`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(0f)
        val morph = morph()
        val loop = backgroundScope.launch(clock + motion) { morph.animateLoading() }

        advanceTimeBy(1_000)
        val scaleFactorReadsWhileHolding = motion.scaleFactorReads
        advanceTimeBy(60_000)

        assertTrue(loop.isActive)
        assertTrue(clock.frameCount <= 2, "frames: ${clock.frameCount}")
        val scaleFactorReadsSinceHolding = motion.scaleFactorReads - scaleFactorReadsWhileHolding
        assertEquals(0, scaleFactorReadsSinceHolding, "scale reads: $scaleFactorReadsSinceHolding")
        assertEquals(loading.first(), morph.button.fromShape)
        assertEquals(loading.first(), morph.button.toShape)
        assertEquals(0f, morph.button.progress)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `turning animations off mid morph holds the next loading shape`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(1f)
        val morph = morph()
        val loop = backgroundScope.launch(clock + motion) { morph.animateLoading() }

        advanceUntilMidMorphBetweenLoadingShapes(morph)
        val targetShape = morph.button.toShape
        val framesAtSwitch = clock.frameCount
        motion.changeSystemScale(0f)
        advanceTimeBy(60_000)

        assertTrue(loop.isActive)
        val framesAfterSwitch = clock.frameCount - framesAtSwitch
        assertTrue(framesAfterSwitch <= 2, "frames: $framesAfterSwitch")
        assertEquals(targetShape, morph.button.fromShape)
        assertEquals(targetShape, morph.button.toShape)
        assertEquals(0f, morph.button.progress)
        assertFalse(morph.showIcon)
    }

    @Test
    fun `re-enabled animations resume the loop`() = runTest {
        val clock = FakeFrameClock()
        val motion = FakeMotionDurationScale(0f)
        val morph = morph()
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
        val morph = morph(connecting = true)
        val loop = launch(clock + motion) { morph.animateLoading() }

        advanceTimeBy(1_000)
        assertTrue(loop.isActive)
        assertEquals(loading.first(), morph.button.fromShape)
        assertFalse(morph.showIcon)
        loop.cancelAndJoin()
        val framesBeforeSettle = clock.frameCount
        launch(clock + motion) { morph.settle() }.join()

        val settleFrames = clock.frameCount - framesBeforeSettle
        assertTrue(settleFrames <= 2, "frames: $settleFrames")
        assertEquals(resting, morph.button.fromShape)
        assertEquals(resting, morph.button.toShape)
        assertEquals(0f, morph.button.progress)
        assertTrue(morph.showIcon)
    }
}

private const val MaxFramesToReachMidMorph = 200
