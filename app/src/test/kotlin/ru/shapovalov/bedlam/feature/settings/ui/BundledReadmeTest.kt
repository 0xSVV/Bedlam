package ru.shapovalov.bedlam.feature.settings.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        val leftovers = inlines(blocks)
            .filterIsInstance<MarkdownInline.Text>()
            .map { it.text }
            .filter { text -> MARKDOWN_SYNTAX.any { it in text } }

        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `every readme link opens an https page`() {
        val links = inlines(blocks).filterIsInstance<MarkdownInline.Link>()

        assertTrue(links.isNotEmpty())
        assertEquals(emptyList<String>(), links.map { it.url }.filterNot { it.startsWith("https://") })
    }

    private fun inlines(blocks: List<MarkdownBlock>): List<MarkdownInline> = blocks.flatMap { block ->
        when (block) {
            is MarkdownBlock.Heading -> inlines(block.content)
            is MarkdownBlock.Paragraph -> inlines(block.content)
            is MarkdownBlock.Quote -> inlines(block.blocks)
            is MarkdownBlock.BulletList -> block.items.flatMap(::inlines)
            is MarkdownBlock.OrderedList -> block.items.flatMap(::inlines)
            is MarkdownBlock.Table -> (block.header + block.rows.flatten()).flatMap { inlines(it) }
            is MarkdownBlock.CodeBlock, MarkdownBlock.Rule -> emptyList()
        }
    }

    @JvmName("nestedInlines")
    private fun inlines(content: List<MarkdownInline>): List<MarkdownInline> = content.flatMap { inline ->
        when (inline) {
            is MarkdownInline.Emphasis -> listOf(inline) + inlines(inline.content)
            is MarkdownInline.Strong -> listOf(inline) + inlines(inline.content)
            is MarkdownInline.Strikethrough -> listOf(inline) + inlines(inline.content)
            is MarkdownInline.Link -> listOf(inline) + inlines(inline.content)
            else -> listOf(inline)
        }
    }

    private companion object {
        val MARKDOWN_SYNTAX = listOf("**", "__", "~~", "`", "](", "<", ">")
    }
}
