package ru.shapovalov.bedlam.feature.update.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.BulletList
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Heading
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Paragraph
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Code
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Link
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Strong
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Text

class ReleaseNotesMarkdownTest {

    @Test
    fun `a bold line becomes a small section heading`() {
        assertEquals(
            listOf(
                Paragraph(listOf(Text("Bedlam 1.6.0 fixes Android 10 and 11."))),
                Heading(4, listOf(Text("DNS"))),
                BulletList(listOf(listOf(Paragraph(listOf(Text("The presets use hostnames.")))))),
            ),
            releaseNotesBlocks("Bedlam 1.6.0 fixes Android 10 and 11.\n\n**DNS**\n\n- The presets use hostnames."),
        )
    }

    @Test
    fun `bold text that shares its paragraph or sits in a bullet stays bold`() {
        assertEquals(
            listOf(
                Paragraph(listOf(Strong(listOf(Text("Raise the limit"))), Text(" on your server."))),
                BulletList(
                    listOf(
                        listOf(
                            Paragraph(
                                listOf(Strong(listOf(Text("Raise "), Code("quic.maxIdleTimeout"), Text(".")))),
                            ),
                        ),
                    ),
                ),
            ),
            releaseNotesBlocks("**Raise the limit** on your server.\n\n- **Raise `quic.maxIdleTimeout`.**"),
        )
    }

    @Test
    fun `headings never outrank the section headings`() {
        assertEquals(
            listOf(Heading(4, listOf(Text("Title"))), Heading(6, listOf(Text("Detail")))),
            releaseNotesBlocks("# Title\n\n###### Detail"),
        )
    }

    @Test
    fun `relative links resolve against the repository`() {
        assertEquals(
            listOf(
                Paragraph(
                    listOf(
                        Text("See "),
                        Link(
                            "https://github.com/0xSVV/Bedlam/blob/master/release-notes/1.5.3.md",
                            listOf(Text("the 1.5.3 notes")),
                        ),
                    ),
                ),
            ),
            releaseNotesBlocks("See [the 1.5.3 notes](release-notes/1.5.3.md)"),
        )
    }

    @Test
    fun `blank notes have no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), releaseNotesBlocks(" \n\n "))
    }
}
