package ru.shapovalov.bedlam.feature.profileconfig.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NumericFieldTextTest {

    private val parseInt: (String) -> Int? = { it.toIntOrNull() }

    @Test
    fun `a focused field keeps its text while the store echoes`() {
        assertEquals("", numericFieldText("", "0", focused = true, parseInt))
        assertEquals("09", numericFieldText("09", "9", focused = true, parseInt))
        assertEquals("-", numericFieldText("-", "30", focused = true, parseInt))
    }

    @Test
    fun `an unfocused field keeps an empty zero and adopts other store values`() {
        assertEquals("", numericFieldText("", "0", focused = false, parseInt))
        assertEquals("45", numericFieldText("30", "45", focused = false, parseInt))
        assertEquals("12", numericFieldText("12a", "12", focused = false, parseInt))
        assertEquals("5", numericFieldText("99999999999", "5", focused = false, parseInt))
    }
}
