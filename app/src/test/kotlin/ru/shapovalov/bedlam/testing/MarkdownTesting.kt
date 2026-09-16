package ru.shapovalov.bedlam.testing

import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline

val LEFTOVER_MARKDOWN_SYNTAX = listOf("**", "__", "~~", "`", "](", "<", ">")

fun List<MarkdownBlock>.allInlines(): List<MarkdownInline> = flatMap { block ->
    when (block) {
        is MarkdownBlock.Heading -> block.content.allInlines()
        is MarkdownBlock.Paragraph -> block.content.allInlines()
        is MarkdownBlock.Quote -> block.blocks.allInlines()
        is MarkdownBlock.BulletList -> block.items.flatMap { it.allInlines() }
        is MarkdownBlock.OrderedList -> block.items.flatMap { it.allInlines() }
        is MarkdownBlock.Table -> (block.header + block.rows.flatten()).flatMap { it.allInlines() }
        is MarkdownBlock.CodeBlock, MarkdownBlock.Rule -> emptyList()
    }
}

@JvmName("nestedInlines")
fun List<MarkdownInline>.allInlines(): List<MarkdownInline> = flatMap { inline ->
    when (inline) {
        is MarkdownInline.Emphasis -> listOf(inline) + inline.content.allInlines()
        is MarkdownInline.Strong -> listOf(inline) + inline.content.allInlines()
        is MarkdownInline.Strikethrough -> listOf(inline) + inline.content.allInlines()
        is MarkdownInline.Link -> listOf(inline) + inline.content.allInlines()
        else -> listOf(inline)
    }
}

fun List<MarkdownBlock>.textWithLeftoverSyntax(): List<String> = allInlines()
    .filterIsInstance<MarkdownInline.Text>()
    .map { it.text }
    .filter { text -> LEFTOVER_MARKDOWN_SYNTAX.any { it in text } }

fun List<MarkdownInline>.plainText(): String = allInlines().joinToString("") { inline ->
    when (inline) {
        is MarkdownInline.Text -> inline.text
        is MarkdownInline.Code -> inline.code
        else -> ""
    }
}
