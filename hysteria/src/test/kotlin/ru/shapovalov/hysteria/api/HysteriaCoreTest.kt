package ru.shapovalov.hysteria.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HysteriaCoreTest {

    @Test
    fun `the core version is the pinned core release tag`() {
        val toolchain = listOf(File("../.github/toolchain.env"), File(".github/toolchain.env")).first { it.isFile }
        val tags = toolchain.readLines()
            .first { it.startsWith("HYSTERIA_CORE_TAGS=") }
            .substringAfter('=')
            .trim()
            .split(Regex("\\s+"))

        assertEquals("core/v${HysteriaCore.VERSION}", tags.single { it.startsWith("core/v") })
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(HysteriaCore.VERSION), HysteriaCore.VERSION)
    }
}
