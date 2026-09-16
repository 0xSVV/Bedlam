package ru.shapovalov.bedlam.ui.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.BulletList
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.CodeBlock
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Heading
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.OrderedList
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Paragraph
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Quote
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Rule
import ru.shapovalov.bedlam.ui.markdown.MarkdownBlock.Table
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Code
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Emphasis
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.LineBreak
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Link
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Strikethrough
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Strong
import ru.shapovalov.bedlam.ui.markdown.MarkdownInline.Text

class MarkdownParserTest {

    private fun parse(markdown: String) = MarkdownParser().parse(markdown)

    private fun inlines(markdown: String): List<MarkdownInline> =
        (parse(markdown).single() as Paragraph).content

    private fun paragraph(text: String) = Paragraph(listOf(Text(text)))

    @Test
    fun `atx headings keep their level and drop the closing hashes`() {
        assertEquals(
            listOf(
                Heading(1, listOf(Text("One"))),
                Heading(2, listOf(Text("Two"))),
                Heading(6, listOf(Text("Six"))),
                paragraph("#hashtag"),
            ),
            parse("# One\n## Two ##\n###### Six\n#hashtag"),
        )
    }

    @Test
    fun `setext underlines turn a paragraph into a heading`() {
        assertEquals(
            listOf(Heading(1, listOf(Text("Title"))), Heading(2, listOf(Text("Subtitle")))),
            parse("Title\n===\n\nSubtitle\n---"),
        )
    }

    @Test
    fun `paragraph lines join with spaces and hard breaks become line breaks`() {
        assertEquals(
            listOf(Text("one two"), LineBreak, Text("three"), LineBreak, Text("four")),
            inlines("one\ntwo  \nthree\\\nfour"),
        )
    }

    @Test
    fun `blank lines separate paragraphs`() {
        assertEquals(listOf(paragraph("first"), paragraph("second")), parse("first\n\n\nsecond\n"))
    }

    @Test
    fun `emphasis strong and strikethrough nest`() {
        assertEquals(
            listOf(
                Emphasis(listOf(Text("a"))),
                Text(" "),
                Strong(listOf(Text("b"))),
                Text(" "),
                Strong(listOf(Emphasis(listOf(Text("c"))))),
                Text(" "),
                Strikethrough(listOf(Text("d"))),
                Text(" "),
                Strong(listOf(Text("e "), Emphasis(listOf(Text("f"))), Text(" g"))),
                Text(" "),
                Emphasis(listOf(Text("h "), Strong(listOf(Text("i"))), Text(" j"))),
            ),
            inlines("*a* **b** ***c*** ~~d~~ __e _f_ g__ *h **i** j*"),
        )
    }

    @Test
    fun `intraword underscores and spaced asterisks stay literal`() {
        assertEquals(
            listOf(Text("snake_case_name, 2 * 3 * 4 and a ** b")),
            inlines("snake_case_name, 2 * 3 * 4 and a ** b"),
        )
    }

    @Test
    fun `code spans keep their content literally`() {
        assertEquals(
            listOf(
                Text("run "),
                Code("a *b* [c](d)"),
                Text(" and "),
                Code("x ` y"),
                Text(" then "),
                Code("padded"),
                Text(" but `open"),
            ),
            inlines("run `a *b* [c](d)` and `` x ` y `` then ` padded ` but `open"),
        )
    }

    @Test
    fun `inline links resolve relative destinations against the base url`() {
        val parser = MarkdownParser("https://github.com/0xSVV/Bedlam/blob/master/README.md")

        val content = (parser.parse(
            "[core](https://github.com/apernet/hysteria) [notes](MAINTAINING.md \"Maintaining\") " +
                    "[top](#routing) [*guide*](<docs/guide.md>)",
        ).single() as Paragraph).content

        assertEquals(
            listOf(
                Link("https://github.com/apernet/hysteria", listOf(Text("core"))),
                Text(" "),
                Link("https://github.com/0xSVV/Bedlam/blob/master/MAINTAINING.md", listOf(Text("notes"))),
                Text(" "),
                Link("https://github.com/0xSVV/Bedlam/blob/master/README.md#routing", listOf(Text("top"))),
                Text(" "),
                Link(
                    "https://github.com/0xSVV/Bedlam/blob/master/docs/guide.md",
                    listOf(Emphasis(listOf(Text("guide")))),
                ),
            ),
            content,
        )
    }

    @Test
    fun `reference links use definitions from anywhere in the document`() {
        assertEquals(
            listOf(
                Paragraph(
                    listOf(
                        Link("https://a.example", listOf(Text("full"))),
                        Text(" "),
                        Link("https://b.example", listOf(Text("collapsed"))),
                        Text(" "),
                        Link("https://c.example", listOf(Text("Shortcut"))),
                    ),
                ),
            ),
            parse(
                "[full][site] [collapsed][] [Shortcut]\n\n" +
                        "[site]: https://a.example\n" +
                        "[COLLAPSED]: https://b.example \"B\"\n" +
                        "[shortcut]: <https://c.example>",
            ),
        )
    }

    @Test
    fun `brackets without a destination stay literal`() {
        assertEquals(listOf(Text("[x] and [ ] and [a](")), inlines("[x] and [ ] and [a]("))
    }

    @Test
    fun `a link inside link text stays text`() {
        assertEquals(
            listOf(Link("https://a.example", listOf(Text("https://a.example")))),
            inlines("[https://a.example](https://a.example)"),
        )
    }

    @Test
    fun `autolinks bare urls and email addresses become links`() {
        assertEquals(
            listOf(
                Link("https://a.example", listOf(Text("https://a.example"))),
                Text(" see "),
                Link("https://b.example/path", listOf(Text("https://b.example/path"))),
                Text("). Mail "),
                Link("mailto:me@c.example", listOf(Text("me@c.example"))),
            ),
            inlines("<https://a.example> see https://b.example/path). Mail <me@c.example>"),
        )
    }

    @Test
    fun `images and html are dropped`() {
        assertEquals(
            listOf(Paragraph(listOf(Text("Text with bold"), LineBreak, Text("next")))),
            parse(
                "<p align=\"center\">\n  <img src=\"logo.png\">\n</p>\n\n" +
                        "Text ![logo](logo.png) with <b>bold</b><br>next [![badge](b.svg)](https://ci.example)\n\n" +
                        "<!--\ncomment\n-->",
            ),
        )
    }

    @Test
    fun `entities and escapes decode to characters`() {
        assertEquals(
            listOf(Text("AT&T © — A *not emphasis* &unknown;")),
            inlines("AT&amp;T &copy; &#8212; &#x41; \\*not emphasis\\* &unknown;"),
        )
    }

    @Test
    fun `bullet lists collect continuation lines and nested lists`() {
        assertEquals(
            listOf(
                BulletList(
                    listOf(
                        listOf(paragraph("one continued")),
                        listOf(paragraph("two"), BulletList(listOf(listOf(paragraph("nested"))))),
                    ),
                ),
                BulletList(listOf(listOf(paragraph("other list")))),
            ),
            parse("- one\n  continued\n- two\n  - nested\n* other list"),
        )
    }

    @Test
    fun `ordered lists keep their start number and loose items`() {
        assertEquals(
            listOf(
                OrderedList(
                    3,
                    listOf(listOf(paragraph("three")), listOf(paragraph("four"), paragraph("more"))),
                ),
                paragraph("after 2. is not a list"),
            ),
            parse("3. three\n\n4. four\n\n   more\n\nafter\n2. is not a list"),
        )
    }

    @Test
    fun `fenced code keeps its content and strips the fence indentation`() {
        assertEquals(
            listOf(CodeBlock("go build\n  indented\n\n*not emphasis*"), CodeBlock("unclosed")),
            parse("  ```sh\n  go build\n    indented\n\n  *not emphasis*\n  ```\n~~~\nunclosed\n"),
        )
    }

    @Test
    fun `indented code needs four spaces outside a paragraph`() {
        assertEquals(
            listOf(CodeBlock("code line\n\n  deeper"), paragraph("text continuation")),
            parse("    code line\n\n      deeper\n\ntext\n    continuation"),
        )
    }

    @Test
    fun `block quotes nest blocks and take lazy continuation lines`() {
        assertEquals(
            listOf(
                Quote(listOf(Heading(1, listOf(Text("Title"))), paragraph("quoted lazy"))),
                Quote(listOf(BulletList(listOf(listOf(paragraph("item")))))),
            ),
            parse("> # Title\n> quoted\nlazy\n\n> - item"),
        )
    }

    @Test
    fun `thematic breaks win over list items`() {
        assertEquals(listOf(Rule, Rule, Rule), parse("* * *\n- - -\n___"))
    }

    @Test
    fun `tables read the alignment and pad missing cells`() {
        assertEquals(
            listOf(
                Table(
                    alignments = listOf(TableAlignment.Start, TableAlignment.Center, TableAlignment.End),
                    header = listOf(listOf(Text("Output")), listOf(Code("abi")), listOf(Text("Code"))),
                    rows = listOf(
                        listOf(listOf(Text("universal")), listOf(Text("all")), listOf(Text("base"))),
                        listOf(listOf(Text("arm64 | v8a")), emptyList(), emptyList()),
                    ),
                ),
                paragraph("after"),
            ),
            parse("| Output | `abi` | Code |\n|:--|:-:|--:|\n| universal | all | base |\n| arm64 \\| v8a |\n\nafter"),
        )
    }

    @Test
    fun `windows line endings parse like unix ones`() {
        assertEquals(
            listOf(Heading(1, listOf(Text("A"))), paragraph("text more")),
            parse("# A\r\n\r\ntext\r\nmore\r\n"),
        )
    }
}
