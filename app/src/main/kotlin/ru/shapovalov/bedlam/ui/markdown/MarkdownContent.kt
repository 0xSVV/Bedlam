package ru.shapovalov.bedlam.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import ru.shapovalov.bedlam.ui.theme.spacing

@Composable
fun MarkdownBlockContent(
    block: MarkdownBlock,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BlockContent(block = block, depth = 0, onOpenUrl = onOpenUrl, modifier = modifier)
}

@Composable
fun markdownBlockSpacing(block: MarkdownBlock): Dp = when {
    block is MarkdownBlock.Heading && block.level <= 2 -> MaterialTheme.spacing.xLarge
    block is MarkdownBlock.Heading -> MaterialTheme.spacing.large
    else -> MaterialTheme.spacing.medium
}

@Composable
private fun BlockContent(
    block: MarkdownBlock,
    depth: Int,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    when (block) {
        is MarkdownBlock.Heading -> InlineText(
            content = block.content,
            style = headingStyle(block.level),
            color = colors.onSurface,
            onOpenUrl = onOpenUrl,
            modifier = modifier.semantics { heading() },
        )

        is MarkdownBlock.Paragraph -> InlineText(
            content = block.content,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            onOpenUrl = onOpenUrl,
            modifier = modifier,
        )

        is MarkdownBlock.CodeBlock -> CodeBlockContent(code = block.code, modifier = modifier)
        is MarkdownBlock.Quote -> QuoteContent(block.blocks, depth, onOpenUrl, modifier)
        is MarkdownBlock.BulletList -> ListContent(block.items, depth, onOpenUrl, modifier) {
            BULLETS[depth % BULLETS.size]
        }

        is MarkdownBlock.OrderedList -> ListContent(block.items, depth, onOpenUrl, modifier) { index ->
            "${block.start + index}."
        }

        is MarkdownBlock.Table -> TableContent(block, onOpenUrl, modifier)
        MarkdownBlock.Rule -> HorizontalDivider(modifier = modifier, color = colors.outlineVariant)
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.titleLarge
    3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun InlineText(
    content: List<MarkdownInline>,
    style: TextStyle,
    color: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val text = remember(content, colors, onOpenUrl) { inlineText(content, colors, onOpenUrl) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        softWrap = softWrap,
        maxLines = if (softWrap) Int.MAX_VALUE else 1,
        style = style,
    )
}

private fun inlineText(
    content: List<MarkdownInline>,
    colors: ColorScheme,
    onOpenUrl: (String) -> Unit,
): AnnotatedString = buildAnnotatedString { appendInlines(content, colors, onOpenUrl) }

private fun AnnotatedString.Builder.appendInlines(
    content: List<MarkdownInline>,
    colors: ColorScheme,
    onOpenUrl: (String) -> Unit,
) {
    content.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)
            is MarkdownInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = CODE_FONT_SCALE.em,
                    background = colors.surfaceContainerHighest,
                ),
            ) {
                append(inline.code)
            }

            is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.content, colors, onOpenUrl)
            }

            is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInlines(inline.content, colors, onOpenUrl)
            }

            is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlines(inline.content, colors, onOpenUrl)
            }

            is MarkdownInline.Link -> withLink(
                LinkAnnotation.Url(
                    url = inline.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
                        pressedStyle = SpanStyle(background = colors.primary.copy(alpha = PRESSED_LINK_ALPHA)),
                    ),
                    linkInteractionListener = { onOpenUrl(inline.url) },
                ),
            ) {
                appendInlines(inline.content, colors, onOpenUrl)
            }

            MarkdownInline.LineBreak -> append('\n')
        }
    }
}

@Composable
private fun CodeBlockContent(code: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = code,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.large),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun QuoteContent(
    blocks: List<MarkdownBlock>,
    depth: Int,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .drawBehind {
                val barWidth = QuoteBarWidth.toPx()
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }
            .padding(start = QuoteBarWidth + MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        blocks.forEach { BlockContent(block = it, depth = depth, onOpenUrl = onOpenUrl) }
    }
}

@Composable
private fun ListContent(
    items: List<List<MarkdownBlock>>,
    depth: Int,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    marker: (Int) -> String,
) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = marker(index),
                    modifier = Modifier
                        .widthIn(min = ListMarkerMinWidth)
                        .padding(end = spacing.small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    item.forEach { BlockContent(block = it, depth = depth + 1, onOpenUrl = onOpenUrl) }
                }
            }
        }
    }
}

@Composable
private fun TableContent(
    table: MarkdownBlock.Table,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(TableLineWidth, colors.outlineVariant),
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            table.alignments.forEachIndexed { column, alignment ->
                Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                    TableCell(
                        content = table.header[column],
                        alignment = alignment,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                        background = colors.surfaceContainerHigh,
                        topLine = null,
                        onOpenUrl = onOpenUrl,
                    )
                    table.rows.forEach { row ->
                        TableCell(
                            content = row[column],
                            alignment = alignment,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            background = Color.Transparent,
                            topLine = colors.outlineVariant,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    content: List<MarkdownInline>,
    alignment: TableAlignment,
    style: TextStyle,
    color: Color,
    background: Color,
    topLine: Color?,
    onOpenUrl: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                if (topLine != null) {
                    drawLine(topLine, Offset.Zero, Offset(size.width, 0f), TableLineWidth.toPx())
                }
            }
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        contentAlignment = when (alignment) {
            TableAlignment.Start -> Alignment.CenterStart
            TableAlignment.Center -> Alignment.Center
            TableAlignment.End -> Alignment.CenterEnd
        },
    ) {
        InlineText(content = content, style = style, color = color, onOpenUrl = onOpenUrl, softWrap = false)
    }
}

private val BULLETS = listOf("•", "◦", "▪")
private const val CODE_FONT_SCALE = 0.9f
private const val PRESSED_LINK_ALPHA = 0.12f
private val QuoteBarWidth = 4.dp
private val ListMarkerMinWidth = 16.dp
private val TableLineWidth = 1.dp
