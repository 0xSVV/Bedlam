package ru.shapovalov.bedlam.feature.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.update.data.signersMatch

class ApkSignatureTest {

    private val a = byteArrayOf(1, 2, 3)
    private val b = byteArrayOf(4, 5, 6)
    private val c = byteArrayOf(7, 8, 9)

    @Test
    fun `a single matching signer is accepted`() {
        assertTrue(signersMatch(listOf(a), listOf(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `a different signer is rejected`() {
        assertFalse(signersMatch(listOf(a), listOf(b)))
    }

    @Test
    fun `signer order does not matter`() {
        assertTrue(signersMatch(listOf(a, b), listOf(byteArrayOf(4, 5, 6), byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `an extra signer on either side is rejected`() {
        assertFalse(signersMatch(listOf(a), listOf(a, b)))
        assertFalse(signersMatch(listOf(a, b), listOf(a)))
    }

    @Test
    fun `a partly overlapping signer set is rejected`() {
        assertFalse(signersMatch(listOf(a, b), listOf(a, c)))
    }

    @Test
    fun `an unreadable side is rejected rather than trusted`() {
        assertFalse(signersMatch(emptyList(), listOf(a)))
        assertFalse(signersMatch(listOf(a), emptyList()))
        assertFalse(signersMatch(emptyList(), emptyList()))
    }

    @Test
    fun `a duplicated signer cannot stand in for a missing one`() {
        assertFalse(signersMatch(listOf(a, b), listOf(a, a)))
        assertFalse(signersMatch(listOf(a, a), listOf(a, b)))
    }

    @Test
    fun `byte content is compared, not identity`() {
        val same = byteArrayOf(1, 2, 3)
        assertFalse(a === same)
        assertTrue(signersMatch(listOf(a), listOf(same)))
    }
}
