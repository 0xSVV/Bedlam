package ru.shapovalov.bedlam.feature.update.ui

import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline
import ru.shapovalov.bedlam.ui.markdown.MarkdownParser

internal fun releaseNotesBlocks(notes: String): List<MarkdownBlock> =
    MarkdownParser(RELEASE_NOTES_BASE_URL).parse(notes).map(::asSectionHeading)

private fun asSectionHeading(block: MarkdownBlock): MarkdownBlock = when (block) {
    is MarkdownBlock.Heading -> block.copy(level = maxOf(block.level, SECTION_HEADING_LEVEL))
    is MarkdownBlock.Paragraph -> (block.content.singleOrNull() as? MarkdownInline.Strong)
        ?.let { MarkdownBlock.Heading(SECTION_HEADING_LEVEL, it.content) }
        ?: block

    else -> block
}

private const val RELEASE_NOTES_BASE_URL = "https://github.com/0xSVV/Bedlam/blob/master/"
private const val SECTION_HEADING_LEVEL = 4
