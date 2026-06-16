package ru.shapovalov.bedlam.core.vpn.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SparklineTest {

    @Test
    fun `peak is the max across both series`() {
        assertEquals(90L, Sparkline.peak(tx = listOf(10, 90), rx = listOf(40, 20)))
    }

    @Test
    fun `peak of empty series is zero`() {
        assertEquals(0L, Sparkline.peak(tx = emptyList(), rx = emptyList()))
    }

    @Test
    fun `fractions normalize against peak`() {
        assertEquals(listOf(0f, 0.5f, 1f), Sparkline.fractions(listOf(0, 50, 100), peak = 100))
    }

    @Test
    fun `fractions are zero when peak is zero`() {
        assertEquals(listOf(0f, 0f), Sparkline.fractions(listOf(0, 0), peak = 0))
    }

    @Test
    fun `fractions clamp to one`() {
        assertEquals(listOf(1f), Sparkline.fractions(listOf(150), peak = 100))
    }
}
