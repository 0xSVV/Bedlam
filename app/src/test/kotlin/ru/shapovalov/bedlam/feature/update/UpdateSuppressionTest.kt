package ru.shapovalov.bedlam.feature.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.update.data.isUpdateSuppressed
import ru.shapovalov.bedlam.feature.update.data.nextSkipCount
import ru.shapovalov.bedlam.feature.update.data.skipsLeft

class UpdateSuppressionTest {

    private val ttl = 6 * 60 * 60 * 1000L
    private val skippedAt = 1_000_000L
    private val skipLimit = 3

    private fun suppressed(
        skippedVersion: String? = "1.3.1",
        skippedAtMillis: Long? = skippedAt,
        skipCount: Int = 1,
        candidate: String = "1.3.1",
        nowMillis: Long = skippedAt,
    ): Boolean = isUpdateSuppressed(
        skippedVersion = skippedVersion,
        skippedAtMillis = skippedAtMillis,
        skipCount = skipCount,
        candidate = candidate,
        nowMillis = nowMillis,
        ttlMillis = ttl,
        skipLimit = skipLimit,
    )

    @Test
    fun `suppressed within the window after skipping the same version`() {
        assertTrue(suppressed(nowMillis = skippedAt))
        assertTrue(suppressed(nowMillis = skippedAt + ttl - 1))
    }

    @Test
    fun `suggested again once the window elapses`() {
        assertFalse(suppressed(nowMillis = skippedAt + ttl))
        assertFalse(suppressed(nowMillis = skippedAt + ttl + 1))
        assertFalse(suppressed(skipCount = skipLimit - 1, nowMillis = skippedAt + ttl))
    }

    @Test
    fun `a version skipped as often as the limit is never suggested again`() {
        assertTrue(suppressed(skipCount = skipLimit, nowMillis = skippedAt + ttl))
        assertTrue(suppressed(skipCount = skipLimit, nowMillis = skippedAt + 365L * ttl))
        assertTrue(suppressed(skipCount = skipLimit + 1, nowMillis = skippedAt - 1))
        assertTrue(suppressed(skippedAtMillis = null, skipCount = skipLimit))
    }

    @Test
    fun `a different version is never suppressed`() {
        assertFalse(suppressed(candidate = "1.4.0"))
        assertFalse(suppressed(skipCount = skipLimit, candidate = "1.4.0"))
    }

    @Test
    fun `nothing skipped means never suppressed`() {
        assertFalse(suppressed(skippedVersion = null, skippedAtMillis = null, skipCount = 0))
        assertFalse(suppressed(skippedAtMillis = null))
    }

    @Test
    fun `clock moved backwards is not suppressed`() {
        assertFalse(suppressed(nowMillis = skippedAt - 1))
    }

    @Test
    fun `skipping the same version again adds to its count`() {
        assertEquals(1, nextSkipCount(skippedVersion = null, skipCount = 0, version = "1.3.1"))
        assertEquals(2, nextSkipCount(skippedVersion = "1.3.1", skipCount = 1, version = "1.3.1"))
        assertEquals(3, nextSkipCount(skippedVersion = "1.3.1", skipCount = 2, version = "1.3.1"))
    }

    @Test
    fun `skipping a newer version starts its count over`() {
        assertEquals(1, nextSkipCount(skippedVersion = "1.3.1", skipCount = 3, version = "1.4.0"))
    }

    private fun skipsLeftFor(skippedVersion: String?, skipCount: Int, version: String = "1.3.1"): Int =
        skipsLeft(skippedVersion, skipCount, version, skipLimit)

    @Test
    fun `skips left count down to zero for the skipped version`() {
        assertEquals(3, skipsLeftFor(skippedVersion = null, skipCount = 0))
        assertEquals(2, skipsLeftFor(skippedVersion = "1.3.1", skipCount = 1))
        assertEquals(1, skipsLeftFor(skippedVersion = "1.3.1", skipCount = 2))
        assertEquals(0, skipsLeftFor(skippedVersion = "1.3.1", skipCount = 3))
        assertEquals(0, skipsLeftFor(skippedVersion = "1.3.1", skipCount = 5))
    }

    @Test
    fun `a version other than the skipped one has every skip left`() {
        assertEquals(3, skipsLeftFor(skippedVersion = "1.3.1", skipCount = 3, version = "1.4.0"))
    }
}
