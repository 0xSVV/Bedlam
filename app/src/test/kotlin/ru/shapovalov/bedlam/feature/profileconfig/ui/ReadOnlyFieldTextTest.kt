package ru.shapovalov.bedlam.feature.profileconfig.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadOnlyFieldTextTest {

    @Test
    fun `a masked secret hides its value`() {
        assertEquals("••••••••", readOnlyFieldText("hunter2", masked = true))
        assertEquals("hunter2", readOnlyFieldText("hunter2", masked = false))
    }

    @Test
    fun `an empty masked secret stays empty`() {
        assertEquals("", readOnlyFieldText("", masked = true))
        assertEquals("  ", readOnlyFieldText("  ", masked = true))
    }
}
