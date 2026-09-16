package ru.shapovalov.bedlam.feature.profileconfig.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListFieldTextTest {

    @Test
    fun `parsing trims entries, splits on commas and newlines, and drops empty ones`() {
        assertEquals(emptyList<String>(), parseListField(""))
        assertEquals(emptyList<String>(), parseListField(" , ,\n"))
        assertEquals(listOf("stun.a:3478"), parseListField("stun.a:3478,"))
        assertEquals(listOf("stun.a:3478"), parseListField("  stun.a:3478 , "))
        assertEquals(
            listOf("stun.a:3478", "stun.b:3478", "stun.c:19302"),
            parseListField("stun.a:3478,stun.b:3478\n stun.c:19302"),
        )
    }

    @Test
    fun `formatting a stored list parses back to the same list`() {
        val stored = listOf("stun.a:3478", "stun.b:3478")

        assertEquals("stun.a:3478, stun.b:3478", formatListField(stored))
        assertEquals("", formatListField(emptyList()))
        assertEquals(stored, parseListField(formatListField(stored)))
    }

    @Test
    fun `a focused field keeps a trailing separator while the store echoes the parsed list`() {
        val stored = listOf("stun.a:3478")

        assertEquals("stun.a:3478,", listFieldText("stun.a:3478,", stored, focused = true))
        assertEquals("stun.a:3478, ", listFieldText("stun.a:3478, ", stored, focused = true))
        assertEquals("stun.a:3478, s", listFieldText("stun.a:3478, s", emptyList(), focused = true))
    }

    @Test
    fun `an unfocused field keeps equivalent text and adopts a different stored list`() {
        val stored = listOf("stun.a:3478")

        assertEquals("stun.a:3478,", listFieldText("stun.a:3478,", stored, focused = false))
        assertEquals("", listFieldText("", emptyList(), focused = false))
        assertEquals(
            "stun.a:3478, stun.b:3478",
            listFieldText("", listOf("stun.a:3478", "stun.b:3478"), focused = false),
        )
        assertEquals("", listFieldText("stun.a:3478", emptyList(), focused = false))
    }
}
