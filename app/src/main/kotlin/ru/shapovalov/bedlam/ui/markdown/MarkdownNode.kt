package ru.shapovalov.bedlam.ui.markdown

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class BulletList(val items: List<List<MarkdownBlock>>) : MarkdownBlock
    data class OrderedList(val start: Int, val items: List<List<MarkdownBlock>>) : MarkdownBlock
    data class Table(
        val alignments: List<TableAlignment>,
        val header: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock
}

enum class TableAlignment { Start, Center, End }

sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline
    data class Code(val code: String) : MarkdownInline
    data class Emphasis(val content: List<MarkdownInline>) : MarkdownInline
    data class Strong(val content: List<MarkdownInline>) : MarkdownInline
    data class Strikethrough(val content: List<MarkdownInline>) : MarkdownInline
    data class Link(val url: String, val content: List<MarkdownInline>) : MarkdownInline
    data object LineBreak : MarkdownInline
}
