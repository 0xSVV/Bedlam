package ru.shapovalov.bedlam.feature.settings.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.allInlines
import ru.shapovalov.bedlam.testing.textWithLeftoverSyntax
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline
import ru.shapovalov.bedlam.ui.markdown.MarkdownParser
import java.io.File

class BundledReadmeTest {

    private val readme = listOf(File("../README.md"), File("README.md")).first { it.isFile }
    private val blocks = MarkdownParser(README_URL).parse(readme.readText())

    @Test
    fun `the readme opens with the app name`() {
        assertEquals(MarkdownBlock.Heading(1, listOf(MarkdownInline.Text("Bedlam"))), blocks.first())
    }

    @Test
    fun `no markdown syntax is left in the readme text`() {
        assertEquals(emptyList<String>(), blocks.textWithLeftoverSyntax())
    }

    @Test
    fun `every readme link opens an https page`() {
        val links = blocks.allInlines().filterIsInstance<MarkdownInline.Link>()

        assertTrue(links.isNotEmpty())
        assertEquals(emptyList<String>(), links.map { it.url }.filterNot { it.startsWith("https://") })
    }
}
