package ru.shapovalov.bedlam.feature.update.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.plainText
import ru.shapovalov.bedlam.testing.textWithLeftoverSyntax
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock
import java.io.File

class ShippedReleaseNotesTest {

    private val notesDirectory = listOf(File("../release-notes"), File("release-notes")).first { it.isDirectory }

    private fun notesFiles(): List<File> =
        notesDirectory.listFiles { file -> file.extension == "md" }.orEmpty().sortedBy { it.name }

    @Test
    fun `no markdown syntax is left in any release notes`() {
        val files = notesFiles()
        assertTrue(files.isNotEmpty())

        val leftovers = files
            .associate { it.name to releaseNotesBlocks(it.readText()).textWithLeftoverSyntax() }
            .filterValues { it.isNotEmpty() }

        assertEquals(emptyMap<String, List<String>>(), leftovers)
    }

    @Test
    fun `every bold line in the release notes becomes a section heading`() {
        val mismatches = notesFiles()
            .associate { file ->
                val notes = file.readText()
                val boldLines = BOLD_LINE.findAll(notes).map { it.groupValues[1].replace("`", "") }.toList()
                val headings = releaseNotesBlocks(notes)
                    .filterIsInstance<MarkdownBlock.Heading>()
                    .map { it.content.plainText() }
                file.name to (boldLines to headings)
            }
            .filterValues { (boldLines, headings) -> boldLines != headings }

        assertEquals(emptyMap<String, Pair<List<String>, List<String>>>(), mismatches)
    }

    private companion object {
        val BOLD_LINE = Regex("""(?m)^\*\*([^*]+)\*\*\s*$""")
    }
}
