package ru.shapovalov.bedlam.feature.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.update.data.isUpdateSuppressed

class UpdateSuppressionTest {

    private val ttl = 6 * 60 * 60 * 1000L
    private val skippedAt = 1_000_000L

    @Test
    fun `suppressed within the window after skipping the same version`() {
        assertTrue(isUpdateSuppressed("1.3.1", skippedAt, "1.3.1", skippedAt, ttl))
        assertTrue(isUpdateSuppressed("1.3.1", skippedAt, "1.3.1", skippedAt + ttl - 1, ttl))
    }

    @Test
    fun `suggested again once the window elapses`() {
        assertFalse(isUpdateSuppressed("1.3.1", skippedAt, "1.3.1", skippedAt + ttl, ttl))
        assertFalse(isUpdateSuppressed("1.3.1", skippedAt, "1.3.1", skippedAt + ttl + 1, ttl))
    }

    @Test
    fun `a different version is never suppressed`() {
        assertFalse(isUpdateSuppressed("1.3.1", skippedAt, "1.4.0", skippedAt, ttl))
    }

    @Test
    fun `nothing skipped means never suppressed`() {
        assertFalse(isUpdateSuppressed(null, null, "1.3.1", skippedAt, ttl))
        assertFalse(isUpdateSuppressed("1.3.1", null, "1.3.1", skippedAt, ttl))
    }

    @Test
    fun `clock moved backwards is not suppressed`() {
        assertFalse(isUpdateSuppressed("1.3.1", skippedAt, "1.3.1", skippedAt - 1, ttl))
    }
}
