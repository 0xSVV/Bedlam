package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.FakeFrameClock
import ru.shapovalov.bedlam.testing.FrameIntervalMillis

@OptIn(ExperimentalCoroutinesApi::class)
class MorphingShapeTest {

    private val triangle = RoundedPolygon(numVertices = 3)
    private val pentagon = RoundedPolygon(numVertices = 5)
    private val heptagon = RoundedPolygon(numVertices = 7)

    @Test
    fun `a morph completes on the next shape`() = runTest {
        val clock = FakeFrameClock()
        val shape = MorphingShape(triangle)

        launch(clock) { shape.morphTo(pentagon) }.join()

        assertTrue(clock.frameCount > 2, "frames: ${clock.frameCount}")
        assertEquals(pentagon, shape.fromShape)
        assertEquals(pentagon, shape.toShape)
        assertEquals(0f, shape.progress)
    }

    @Test
    fun `morphing to the shape in flight continues instead of reversing`() = runTest {
        val clock = FakeFrameClock()
        val shape = MorphingShape(triangle)
        val first = launch(clock) { shape.morphTo(pentagon) }
        advanceTimeBy(FrameIntervalMillis * 8)
        first.cancelAndJoin()
        val progressAtInterruption = shape.progress
        assertTrue(progressAtInterruption > 0f && progressAtInterruption < 1f, "$progressAtInterruption")
        var minProgress = progressAtInterruption
        clock.beforeEachFrame = { minProgress = minOf(minProgress, shape.progress) }

        launch(clock) { shape.morphTo(pentagon) }.join()

        assertEquals(progressAtInterruption, minProgress)
        assertEquals(pentagon, shape.fromShape)
        assertEquals(pentagon, shape.toShape)
        assertEquals(0f, shape.progress)
    }

    @Test
    fun `morphing to another shape returns to the current shape first`() = runTest {
        val clock = FakeFrameClock()
        val shape = MorphingShape(triangle)
        val first = launch(clock) { shape.morphTo(pentagon) }
        advanceTimeBy(FrameIntervalMillis * 8)
        first.cancelAndJoin()
        var fromShapeWhenTurning: RoundedPolygon? = null
        var progressWhenTurning = -1f
        clock.beforeEachFrame = {
            if (shape.toShape == heptagon && fromShapeWhenTurning == null) {
                fromShapeWhenTurning = shape.fromShape
                progressWhenTurning = shape.progress
            }
        }

        launch(clock) { shape.morphTo(heptagon) }.join()

        assertEquals(triangle, fromShapeWhenTurning)
        assertEquals(0f, progressWhenTurning)
        assertEquals(heptagon, shape.fromShape)
        assertEquals(heptagon, shape.toShape)
        assertEquals(0f, shape.progress)
    }

    @Test
    fun `returning to the current shape reverses an interrupted morph`() = runTest {
        val clock = FakeFrameClock()
        val shape = MorphingShape(triangle)
        val first = launch(clock) { shape.morphTo(pentagon) }
        advanceTimeBy(FrameIntervalMillis * 8)
        first.cancelAndJoin()
        val framesBefore = clock.frameCount

        launch(clock) { shape.returnToCurrentShape() }.join()

        assertTrue(clock.frameCount > framesBefore)
        assertEquals(triangle, shape.fromShape)
        assertEquals(triangle, shape.toShape)
        assertEquals(0f, shape.progress)
    }

    @Test
    fun `snapping and morphing to the current shape need no frame`() = runTest {
        val clock = FakeFrameClock()
        val shape = MorphingShape(triangle)

        launch(clock) { shape.snapTo(pentagon) }.join()
        launch(clock) { shape.morphTo(pentagon) }.join()
        launch(clock) { shape.returnToCurrentShape() }.join()

        assertEquals(0, clock.frameCount)
        assertEquals(pentagon, shape.fromShape)
        assertEquals(pentagon, shape.toShape)
        assertEquals(0f, shape.progress)
    }
}
