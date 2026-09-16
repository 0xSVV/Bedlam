package ru.shapovalov.bedlam.ui.markdown

import java.net.URI

class MarkdownParser(private val baseUrl: String? = null) {

    fun parse(markdown: String): List<MarkdownBlock> {
        val lines = markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\t", "    ")
            .split('\n')
        val references = mutableMapOf<String, String>()
        BlockParser(references, InlineParser(references, baseUrl)).parse(lines)
        return BlockParser(references, InlineParser(references, baseUrl)).parse(lines)
    }
}

private class BlockParser(
    private val references: MutableMap<String, String>,
    private val inlines: InlineParser,
) {

    fun parse(lines: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0
        while (index < lines.size) {
            index = parseBlock(lines, index, blocks)
        }
        return blocks
    }

    private fun parseBlock(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val line = lines[start]
        val fence = openingFence(line)
        val heading = HEADING.matchEntire(line)
        val marker = listMarker(line)
        return when {
            line.isBlank() -> start + 1
            fence != null -> parseFencedCode(lines, start, fence, blocks)
            heading != null -> {
                val level = heading.groupValues[1].length
                blocks += MarkdownBlock.Heading(level, inlines.parse(heading.groupValues[2]))
                start + 1
            }

            RULE.matches(line) -> {
                blocks += MarkdownBlock.Rule
                start + 1
            }

            QUOTE.containsMatchIn(line) -> parseQuote(lines, start, blocks)
            marker != null -> parseList(lines, start, marker, blocks)
            indentOf(line) >= CODE_INDENT -> parseIndentedCode(lines, start, blocks)
            HTML_BLOCK.containsMatchIn(line) -> skipHtml(lines, start)
            addReference(line) -> start + 1
            isTableStart(lines, start) -> parseTable(lines, start, blocks)
            else -> parseParagraph(lines, start, blocks)
        }
    }

    private fun parseFencedCode(
        lines: List<String>,
        start: Int,
        fence: Fence,
        blocks: MutableList<MarkdownBlock>,
    ): Int {
        var index = start + 1
        while (index < lines.size && !closesFence(lines[index], fence)) index++
        val closed = index < lines.size
        val code = lines.subList(start + 1, index)
            .map { it.removeIndent(fence.indent) }
            .let { if (closed) it else it.dropLastWhile(String::isBlank) }
        blocks += MarkdownBlock.CodeBlock(code.joinToString("\n"))
        return if (closed) index + 1 else index
    }

    private fun parseQuote(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val content = mutableListOf<String>()
        var index = start
        while (index < lines.size) {
            val line = lines[index]
            val prefix = QUOTE.find(line)
            when {
                prefix != null -> content += line.substring(prefix.range.last + 1)
                line.isBlank() || content.last().isBlank() || interruptsParagraph(line) -> break
                else -> content += line
            }
            index++
        }
        blocks += MarkdownBlock.Quote(parse(content))
        return index
    }

    private fun parseList(
        lines: List<String>,
        start: Int,
        first: ListMarker,
        blocks: MutableList<MarkdownBlock>,
    ): Int {
        val items = mutableListOf(mutableListOf(first.content))
        var contentIndent = first.contentIndent
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                val next = (index + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                if (!continuesList(lines[next], first, contentIndent)) break
                repeat(next - index) { items.last() += "" }
                index = next
                continue
            }
            val marker = listMarker(line)
            when {
                indentOf(line) >= contentIndent -> items.last() += line.substring(contentIndent)
                marker != null && !RULE.matches(line) -> {
                    if (!marker.belongsTo(first)) break
                    items += mutableListOf(marker.content)
                    contentIndent = marker.contentIndent
                }

                items.last().last().isBlank() || interruptsParagraph(line) -> break
                else -> items.last() += line.trimStart()
            }
            index++
        }
        val parsedItems = items.map(::parse)
        blocks += when (val number = first.number) {
            null -> MarkdownBlock.BulletList(parsedItems)
            else -> MarkdownBlock.OrderedList(number, parsedItems)
        }
        return index
    }

    private fun continuesList(line: String, first: ListMarker, contentIndent: Int): Boolean {
        if (indentOf(line) >= contentIndent) return true
        val marker = listMarker(line) ?: return false
        return marker.belongsTo(first) && !RULE.matches(line)
    }

    private fun parseIndentedCode(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        var index = start
        while (index < lines.size && (lines[index].isBlank() || indentOf(lines[index]) >= CODE_INDENT)) index++
        val code = lines.subList(start, index)
            .map { it.removeIndent(CODE_INDENT) }
            .dropLastWhile(String::isBlank)
        blocks += MarkdownBlock.CodeBlock(code.joinToString("\n"))
        return index
    }

    private fun skipHtml(lines: List<String>, start: Int): Int {
        if (HTML_COMMENT.containsMatchIn(lines[start])) {
            val end = (start until lines.size).firstOrNull { "-->" in lines[it] } ?: return lines.size
            return end + 1
        }
        var index = start
        while (index < lines.size && lines[index].isNotBlank()) index++
        return index
    }

    private fun addReference(line: String): Boolean {
        val match = REFERENCE.matchEntire(line) ?: return false
        val label = normalizeLabel(match.groupValues[1])
        if (label !in references) {
            references[label] = match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
        return true
    }

    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        val delimiter = lines.getOrNull(index + 1) ?: return false
        return '|' in lines[index] &&
                '|' in delimiter &&
                TABLE_DELIMITER.matches(delimiter) &&
                splitCells(lines[index]).size == splitCells(delimiter).size
    }

    private fun parseTable(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val header = splitCells(lines[start])
        val alignments = splitCells(lines[start + 1]).map { cell ->
            when {
                cell.startsWith(':') && cell.endsWith(':') -> TableAlignment.Center
                cell.endsWith(':') -> TableAlignment.End
                else -> TableAlignment.Start
            }
        }
        val rows = mutableListOf<List<List<MarkdownInline>>>()
        var index = start + 2
        while (index < lines.size && lines[index].isNotBlank() && !interruptsParagraph(lines[index])) {
            val cells = splitCells(lines[index])
            rows += header.indices.map { column -> inlines.parse(cells.getOrElse(column) { "" }) }
            index++
        }
        blocks += MarkdownBlock.Table(alignments, header.map(inlines::parse), rows)
        return index
    }

    private fun splitCells(row: String): List<String> {
        val content = row.trim()
            .removePrefix("|")
            .let { if (it.endsWith('|') && !it.endsWith("\\|")) it.dropLast(1) else it }
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < content.length) {
            when {
                content.startsWith("\\|", index) -> {
                    cell.append('|')
                    index++
                }

                content[index] == '|' -> {
                    cells += cell.toString().trim()
                    cell.clear()
                }

                else -> cell.append(content[index])
            }
            index++
        }
        cells += cell.toString().trim()
        return cells
    }

    private fun parseParagraph(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val content = mutableListOf(lines[start])
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            val underline = SETEXT_UNDERLINE.matchEntire(line)
            if (underline != null) {
                val level = if (underline.groupValues[1].startsWith('=')) 1 else 2
                blocks += MarkdownBlock.Heading(level, inlines.parse(joinLines(content)))
                return index + 1
            }
            if (interruptsParagraph(line)) break
            content += line
            index++
        }
        blocks += MarkdownBlock.Paragraph(inlines.parse(joinLines(content)))
        return index
    }

    private fun interruptsParagraph(line: String): Boolean {
        if (openingFence(line) != null) return true
        if (HEADING.matches(line) || RULE.matches(line) || QUOTE.containsMatchIn(line)) return true
        val marker = listMarker(line) ?: return false
        return marker.content.isNotBlank() && (marker.number == null || marker.number == 1)
    }

    private fun joinLines(lines: List<String>): String = buildString {
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (index == lines.lastIndex) {
                append(line)
                return@forEachIndexed
            }
            val backslashBreak = line.takeLastWhile { it == '\\' }.length % 2 == 1
            append(if (backslashBreak) line.dropLast(1) else line)
            append(if (backslashBreak || raw.endsWith("  ")) '\n' else ' ')
        }
    }

    private fun openingFence(line: String): Fence? {
        val match = FENCE.matchEntire(line) ?: return null
        val marker = match.groupValues[2]
        if (marker.first() == '`' && '`' in match.groupValues[3]) return null
        return Fence(indent = match.groupValues[1].length, marker = marker)
    }

    private fun closesFence(line: String, fence: Fence): Boolean {
        val marker = CLOSING_FENCE.matchEntire(line)?.groupValues?.get(1) ?: return false
        return marker.first() == fence.marker.first() && marker.length >= fence.marker.length
    }

    private fun listMarker(line: String): ListMarker? {
        val match = LIST_ITEM.matchEntire(line) ?: return null
        val indent = match.groupValues[1].length
        val marker = match.groupValues[2]
        val spaces = match.groupValues[3].length
        val text = match.groupValues[4]
        val markerEnd = indent + marker.length
        val (contentIndent, content) = when {
            text.isBlank() -> markerEnd + 1 to ""
            spaces > CODE_INDENT -> markerEnd + 1 to " ".repeat(spaces - 1) + text
            else -> markerEnd + spaces to text
        }
        return ListMarker(
            number = marker.dropLast(1).toIntOrNull(),
            delimiter = marker.last(),
            contentIndent = contentIndent,
            content = content,
        )
    }

    private class Fence(val indent: Int, val marker: String)

    private class ListMarker(
        val number: Int?,
        val delimiter: Char,
        val contentIndent: Int,
        val content: String,
    ) {
        fun belongsTo(list: ListMarker): Boolean =
            (number == null) == (list.number == null) && delimiter == list.delimiter
    }
}

private class InlineParser(
    private val references: Map<String, String>,
    private val baseUrl: String?,
) {

    fun parse(source: String): List<MarkdownInline> = parse(source, linksAllowed = true).trimLineEdges()

    private fun parse(source: String, linksAllowed: Boolean): List<MarkdownInline> {
        val nodes = mutableListOf<MarkdownInline>()
        var index = 0
        while (index < source.length) {
            val parsed = parseAt(source, index, linksAllowed)
            if (parsed != null) {
                nodes += parsed.nodes
                index = parsed.end
            } else {
                val end = plainTextEnd(source, index + 1, linksAllowed)
                nodes += MarkdownInline.Text(source.substring(index, end))
                index = end
            }
        }
        return nodes.mergeText()
    }

    private fun plainTextEnd(source: String, from: Int, linksAllowed: Boolean): Int {
        var index = from
        while (
            index < source.length &&
            source[index] !in SPECIAL_CHARACTERS &&
            !(linksAllowed && startsBareUrl(source, index))
        ) {
            index++
        }
        return index
    }

    private fun parseAt(source: String, index: Int, linksAllowed: Boolean): Parsed? = when (source[index]) {
        '\\' -> parseEscape(source, index)
        '\n' -> Parsed(listOf(MarkdownInline.LineBreak), index + 1)
        '`' -> parseCodeSpan(source, index)
        '!' -> parseImage(source, index)
        '[' -> if (linksAllowed) parseLink(source, index) else null
        '<' -> parseAngleBracket(source, index, linksAllowed)
        '*', '_', '~' -> parseDelimited(source, index, linksAllowed)
        '&' -> parseEntity(source, index)
        'h' -> if (linksAllowed) parseBareUrl(source, index) else null
        else -> null
    }

    private fun parseEscape(source: String, index: Int): Parsed? {
        val escaped = source.getOrNull(index + 1)?.takeIf { it in ESCAPABLE_CHARACTERS } ?: return null
        return Parsed(listOf(MarkdownInline.Text(escaped.toString())), index + 2)
    }

    private fun parseCodeSpan(source: String, index: Int): Parsed {
        val length = runLength(source, index, '`')
        val close = findRun(source, index + length, '`', length)
            ?: return Parsed(listOf(MarkdownInline.Text("`".repeat(length))), index + length)
        val raw = source.substring(index + length, close).replace('\n', ' ')
        val code = if (raw.startsWith(' ') && raw.endsWith(' ') && raw.isNotBlank()) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return Parsed(listOf(MarkdownInline.Code(code)), close + length)
    }

    private fun parseImage(source: String, index: Int): Parsed? {
        if (source.getOrNull(index + 1) != '[') return null
        val parts = parseLinkParts(source, index + 1) ?: return null
        return Parsed(emptyList(), parts.end)
    }

    private fun parseLink(source: String, index: Int): Parsed? {
        val parts = parseLinkParts(source, index) ?: return null
        val content = parse(parts.label, linksAllowed = false)
        val nodes = if (content.isEmpty()) {
            emptyList()
        } else {
            listOf(MarkdownInline.Link(resolve(parts.destination), content))
        }
        return Parsed(nodes, parts.end)
    }

    private fun parseLinkParts(source: String, open: Int): LinkParts? {
        val close = findClosingBracket(source, open) ?: return null
        val label = source.substring(open + 1, close)
        when (source.getOrNull(close + 1)) {
            '(' -> parseInlineDestination(source, close + 1)?.let { (destination, end) ->
                return LinkParts(label, destination, end)
            }

            '[' -> {
                val referenceClose = source.indexOf(']', close + 2)
                if (referenceClose >= 0) {
                    val reference = source.substring(close + 2, referenceClose).ifBlank { label }
                    return references[normalizeLabel(reference)]?.let { LinkParts(label, it, referenceClose + 1) }
                }
            }
        }
        return references[normalizeLabel(label)]?.let { LinkParts(label, it, close + 1) }
    }

    private fun findClosingBracket(source: String, open: Int): Int? {
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index++
                '`' -> index = skipCodeSpan(source, index) - 1
                '[' -> depth++
                ']' -> if (--depth == 0) return index
            }
            index++
        }
        return null
    }

    private fun parseInlineDestination(source: String, openParenthesis: Int): Pair<String, Int>? {
        var index = skipWhitespace(source, openParenthesis + 1)
        val destination: String
        if (source.getOrNull(index) == '<') {
            val close = source.indexOf('>', index + 1).takeIf { it >= 0 } ?: return null
            destination = source.substring(index + 1, close)
            index = close + 1
        } else {
            val start = index
            var depth = 0
            while (index < source.length && !source[index].isWhitespace()) {
                when (source[index]) {
                    '\\' -> if (index + 1 < source.length) index++
                    '(' -> depth++
                    ')' -> if (depth-- == 0) break
                }
                index++
            }
            destination = unescape(source.substring(start, index))
        }
        index = skipWhitespace(source, index)
        val titleClose = when (source.getOrNull(index)) {
            '"' -> '"'
            '\'' -> '\''
            '(' -> ')'
            else -> null
        }
        if (titleClose != null) {
            val close = source.indexOf(titleClose, index + 1).takeIf { it >= 0 } ?: return null
            index = skipWhitespace(source, close + 1)
        }
        if (source.getOrNull(index) != ')') return null
        return destination to index + 1
    }

    private fun parseAngleBracket(source: String, index: Int, linksAllowed: Boolean): Parsed? {
        AUTOLINK.matchAt(source, index)?.let { match ->
            val target = match.groupValues[1]
            val text = MarkdownInline.Text(target)
            val url = if (':' in target) target else "mailto:$target"
            val node = if (linksAllowed) MarkdownInline.Link(url, listOf(text)) else text
            return Parsed(listOf(node), match.range.last + 1)
        }
        LINE_BREAK_TAG.matchAt(source, index)?.let {
            return Parsed(listOf(MarkdownInline.LineBreak), it.range.last + 1)
        }
        HTML_TAG.matchAt(source, index)?.let {
            return Parsed(emptyList(), it.range.last + 1)
        }
        return null
    }

    private fun parseDelimited(source: String, index: Int, linksAllowed: Boolean): Parsed {
        val delimiter = source[index]
        val length = runLength(source, index, delimiter)
        val literal = Parsed(listOf(MarkdownInline.Text(source.substring(index, index + length))), index + length)
        if (!canOpen(source, index, length, delimiter)) return literal
        if (delimiter == '~') {
            if (length != 2) return literal
            val close = findCloser(source, index + 2, delimiter, 2) ?: return literal
            val content = parse(source.substring(index + 2, close), linksAllowed)
            return Parsed(listOf(MarkdownInline.Strikethrough(content)), close + 2)
        }
        if (length >= 2) {
            findCloser(source, index + 2, delimiter, 2)?.let { close ->
                val content = parse(source.substring(index + 2, close), linksAllowed)
                return Parsed(listOf(MarkdownInline.Strong(content)), close + 2)
            }
        }
        val close = findCloser(source, index + 1, delimiter, 1) ?: return literal
        val content = parse(source.substring(index + 1, close), linksAllowed)
        return Parsed(listOf(MarkdownInline.Emphasis(content)), close + 1)
    }

    private fun findCloser(source: String, from: Int, delimiter: Char, width: Int): Int? {
        var index = from
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                '`' -> index = skipCodeSpan(source, index)
                '[' -> index = parseLinkParts(source, index)?.end ?: (index + 1)
                '<' -> index = AUTOLINK.matchAt(source, index)?.let { it.range.last + 1 } ?: (index + 1)
                delimiter -> {
                    val length = runLength(source, index, delimiter)
                    val fits = length == width || length > 2
                    if (index > from && fits && canClose(source, index, length, delimiter)) {
                        return index + length - width
                    }
                    index += length
                }

                else -> index++
            }
        }
        return null
    }

    private fun canOpen(source: String, index: Int, length: Int, delimiter: Char): Boolean {
        val next = source.getOrNull(index + length) ?: return false
        if (next.isWhitespace()) return false
        val previous = source.getOrNull(index - 1)
        return delimiter != '_' || previous == null || !previous.isLetterOrDigit()
    }

    private fun canClose(source: String, index: Int, length: Int, delimiter: Char): Boolean {
        val previous = source.getOrNull(index - 1) ?: return false
        if (previous.isWhitespace()) return false
        val next = source.getOrNull(index + length)
        return delimiter != '_' || next == null || !next.isLetterOrDigit()
    }

    private fun parseEntity(source: String, index: Int): Parsed? {
        val match = ENTITY.matchAt(source, index) ?: return null
        val name = match.groupValues[1]
        val decoded = when {
            name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith('#') -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED_ENTITIES[name]
        } ?: return null
        return Parsed(listOf(MarkdownInline.Text(decoded)), match.range.last + 1)
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..Character.MAX_CODE_POINT) String(Character.toChars(value)) else null

    private fun parseBareUrl(source: String, index: Int): Parsed? {
        if (!startsBareUrl(source, index)) return null
        var end = index
        while (end < source.length && !source[end].isWhitespace() && source[end] != '<') end++
        var url = source.substring(index, end).trimEnd { it in URL_TRAILING_PUNCTUATION }
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) {
            url = url.dropLast(1).trimEnd { it in URL_TRAILING_PUNCTUATION }
        }
        if (url.substringAfter("://").isEmpty()) return null
        return Parsed(listOf(MarkdownInline.Link(url, listOf(MarkdownInline.Text(url)))), index + url.length)
    }

    private fun startsBareUrl(source: String, index: Int): Boolean {
        if (!source.startsWith("https://", index) && !source.startsWith("http://", index)) return false
        val previous = source.getOrNull(index - 1)
        return previous == null || previous.isWhitespace() || previous in "(*_~"
    }

    private fun skipCodeSpan(source: String, index: Int): Int {
        val length = runLength(source, index, '`')
        val close = findRun(source, index + length, '`', length) ?: return index + length
        return close + length
    }

    private fun resolve(destination: String): String {
        val base = baseUrl ?: return destination
        return runCatching { URI(base).resolve(destination).toString() }.getOrDefault(destination)
    }

    private fun unescape(text: String): String = ESCAPED_CHARACTER.replace(text) { it.groupValues[1] }

    private fun skipWhitespace(source: String, from: Int): Int {
        var index = from
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }

    private fun List<MarkdownInline>.mergeText(): List<MarkdownInline> {
        val merged = mutableListOf<MarkdownInline>()
        forEach { node ->
            val previous = merged.lastOrNull()
            if (node is MarkdownInline.Text && previous is MarkdownInline.Text) {
                merged[merged.lastIndex] = MarkdownInline.Text(previous.text + node.text)
            } else {
                merged += node
            }
        }
        return merged.map { node ->
            if (node is MarkdownInline.Text) MarkdownInline.Text(node.text.replace(REPEATED_SPACES, " ")) else node
        }
    }

    private fun List<MarkdownInline>.trimLineEdges(): List<MarkdownInline> = mapIndexedNotNull { index, node ->
        if (node !is MarkdownInline.Text) return@mapIndexedNotNull node
        val startsLine = index == 0 || get(index - 1) == MarkdownInline.LineBreak
        val endsLine = index == lastIndex || get(index + 1) == MarkdownInline.LineBreak
        node.text
            .let { if (startsLine) it.trimStart() else it }
            .let { if (endsLine) it.trimEnd() else it }
            .takeIf { it.isNotEmpty() }
            ?.let(MarkdownInline::Text)
    }

    private class Parsed(val nodes: List<MarkdownInline>, val end: Int)

    private class LinkParts(val label: String, val destination: String, val end: Int)
}

private fun indentOf(line: String): Int = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }

private fun String.removeIndent(count: Int): String = drop(minOf(count, indentOf(this)))

private fun normalizeLabel(label: String): String = label.trim().replace(WHITESPACE, " ").lowercase()

private fun runLength(source: String, index: Int, char: Char): Int {
    var end = index
    while (end < source.length && source[end] == char) end++
    return end - index
}

private fun findRun(source: String, from: Int, char: Char, length: Int): Int? {
    var index = source.indexOf(char, from)
    while (index >= 0) {
        val run = runLength(source, index, char)
        if (run == length) return index
        index = source.indexOf(char, index + run)
    }
    return null
}

private const val CODE_INDENT = 4
private const val SPECIAL_CHARACTERS = "\\\n`![<*_~&"
private const val ESCAPABLE_CHARACTERS = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private const val URL_TRAILING_PUNCTUATION = "?!.,:*_~'\""

private val HEADING = Regex("""^ {0,3}(#{1,6})(?:\s+(.*?))?(?:\s+#+)?\s*$""")
private val RULE = Regex("""^ {0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$""")
private val SETEXT_UNDERLINE = Regex("""^ {0,3}(=+|-+)\s*$""")
private val QUOTE = Regex("""^ {0,3}> ?""")
private val FENCE = Regex("""^( {0,3})(`{3,}|~{3,})(.*)$""")
private val CLOSING_FENCE = Regex("""^ {0,3}(`{3,}|~{3,})\s*$""")
private val LIST_ITEM = Regex("""^( {0,3})([-+*]|\d{1,9}[.)])(?:( +)(.*))?$""")
private val HTML_BLOCK = Regex("""^ {0,3}<(?:!--|/?[A-Za-z][A-Za-z0-9-]*(?:[\s/>]|$))""")
private val HTML_COMMENT = Regex("""^ {0,3}<!--""")
private val REFERENCE =
    Regex("""^ {0,3}\[([^\]]+)]:\s*(?:<([^>]*)>|(\S+))(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*$""")
private val TABLE_DELIMITER = Regex("""^ {0,3}\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?\s*$""")
private val AUTOLINK =
    Regex("""<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>]*|[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)>""")
private val LINE_BREAK_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""<(?:/?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?|!--.*?--)>""")
private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")
private val ESCAPED_CHARACTER = Regex("""\\([!-/:-@\[-`{-~])""")
private val WHITESPACE = Regex("""\s+""")
private val REPEATED_SPACES = Regex(""" {2,}""")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to "\u00A0",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "mdash" to "—",
    "ndash" to "–",
    "hellip" to "…",
    "middot" to "·",
    "times" to "×",
    "larr" to "←",
    "rarr" to "→",
)
