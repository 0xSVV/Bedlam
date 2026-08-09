package ru.shapovalov.bedlam.feature.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.update.data.isCheckThrottled

class UpdateThrottleTest {

    private val interval = 6 * 60 * 60 * 1000L
    private val lastCheck = 1_000_000L

    @Test
    fun `the first check is never throttled`() {
        assertFalse(isCheckThrottled(null, lastCheck, interval))
    }

    @Test
    fun `a check inside the window is throttled`() {
        assertTrue(isCheckThrottled(lastCheck, lastCheck, interval))
        assertTrue(isCheckThrottled(lastCheck, lastCheck + 1, interval))
        assertTrue(isCheckThrottled(lastCheck, lastCheck + interval - 1, interval))
    }

    @Test
    fun `a check once the window elapses is allowed`() {
        assertFalse(isCheckThrottled(lastCheck, lastCheck + interval, interval))
        assertFalse(isCheckThrottled(lastCheck, lastCheck + interval + 1, interval))
    }

    @Test
    fun `a clock moved backwards does not throttle forever`() {
        assertFalse(isCheckThrottled(lastCheck, lastCheck - 1, interval))
        assertFalse(isCheckThrottled(lastCheck, 0, interval))
    }
}
