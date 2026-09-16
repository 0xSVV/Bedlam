package ru.shapovalov.bedlam.core.vpn.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SparklineTest {

    @Test
    fun `peak is the max of the series above the floor`() {
        assertEquals(300_000L, Sparkline.peak(listOf(100_000, 300_000, 40_000)))
    }

    @Test
    fun `peak floors at 64 KiB per second`() {
        assertEquals(65_536L, Sparkline.peak(listOf(1_000, 2_000)))
    }

    @Test
    fun `peak of empty series is the floor`() {
        assertEquals(65_536L, Sparkline.peak(emptyList()))
    }

    @Test
    fun `fractions follow the square root of the share of the peak`() {
        assertEquals(listOf(0f, 0.5f, 1f), Sparkline.fractions(listOf(0, 25, 100), peak = 100))
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
