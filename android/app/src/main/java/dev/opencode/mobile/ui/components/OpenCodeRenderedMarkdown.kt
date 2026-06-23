package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.markdown.DiffLineKind
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.isLikelyExpandable
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class OpenCodeRenderedMarkdownVariant(
    val wireName: String,
) {
    AssistantMessage("assistantMessage"),
    OutgoingMessage("outgoingMessage"),
    Standalone("standalone"),
}

object OpenCodeRenderedMarkdownDetector {
    fun shouldRender(text: String): Boolean = OpenCodeMarkdownDetector.shouldRender(text)
}

@Composable
fun OpenCodeRenderedMarkdown(
    document: MarkdownDocument,
    variant: OpenCodeRenderedMarkdownVariant,
    modifier: Modifier = Modifier,
    initialHeight: Dp = 1.dp,
    collapsedMaxHeight: Dp? = null,
    expanded: Boolean = true,
    onLinkTap: ((String) -> Unit)? = null,
    onExpandableChange: (Boolean) -> Unit = {},
    onContentHeightChange: (Dp) -> Unit = {},
) {
    val density = LocalDensity.current
    val isExpandable = collapsedMaxHeight != null && document.isLikelyExpandable()
    val visibleModifier =
        if (!expanded && collapsedMaxHeight != null && isExpandable) {
            modifier.heightIn(max = collapsedMaxHeight).clipToBounds()
        } else {
            modifier
        }

    LaunchedEffect(document, collapsedMaxHeight, isExpandable) {
        onExpandableChange(isExpandable)
    }

    Column(
        modifier =
            visibleModifier.onSizeChanged { size ->
                val height = with(density) { size.height.toDp() }
                if (height.value.roundToInt() > 0) {
                    onContentHeightChange(height)
                }
            },
        verticalArrangement = Arrangement.spacedBy(blockSpacingFor(variant)),
    ) {
        document.blocks.forEach { block ->
            MarkdownBlockView(
                block = block,
                variant = variant,
                onLinkTap = onLinkTap,
            )
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    variant: OpenCodeRenderedMarkdownVariant,
    onLinkTap: ((String) -> Unit)?,
) {
    when (block) {
        is MarkdownBlock.Paragraph ->
            MarkdownInlineText(
                inlines = block.inlines,
                style = MaterialTheme.typography.bodyMedium,
                onLinkTap = onLinkTap,
            )
        is MarkdownBlock.Heading ->
            MarkdownInlineText(
                inlines = block.inlines,
                style = headingStyle(block.level),
                color = headingColor(block.level),
                onLinkTap = onLinkTap,
                modifier = Modifier.padding(top = if (variant == OpenCodeRenderedMarkdownVariant.Standalone) 2.dp else 0.dp),
            )
        is MarkdownBlock.BlockQuote ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height(26.dp)
                            .background(openCodeColor("markdown-quote-bar"), RoundedCornerShape(2.dp)),
                )
                MarkdownInlineText(
                    inlines = block.inlines,
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("markdown-quote-text"),
                    onLinkTap = onLinkTap,
                    modifier = Modifier.weight(1f),
                )
            }
        is MarkdownBlock.BulletList ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { item ->
                    ListRow(marker = "•", inlines = item.inlines, onLinkTap = onLinkTap)
                }
            }
        is MarkdownBlock.OrderedList ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEachIndexed { index, item ->
                    ListRow(marker = "${block.start + index}.", inlines = item.inlines, onLinkTap = onLinkTap)
                }
            }
        is MarkdownBlock.CodeBlock -> CodeBlockView(block)
        is MarkdownBlock.DiffBlock -> DiffBlockView(block)
        is MarkdownBlock.Table -> TableView(block, onLinkTap)
        MarkdownBlock.ThematicBreak ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(openCodeColor("markdown-hr")),
            )
    }
}

@Composable
private fun ListRow(
    marker: String,
    inlines: List<MarkdownInline>,
    onLinkTap: ((String) -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("markdown-list-marker"),
            modifier = Modifier.width(24.dp),
        )
        MarkdownInlineText(
            inlines = inlines,
            style = MaterialTheme.typography.bodyMedium,
            onLinkTap = onLinkTap,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    val scrollState = rememberScrollState()
    val colors = codeColors()
    val annotated = rememberCodeAnnotatedString(block.code, block.spans, colors)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SelectionContainer {
            Text(
                text = annotated,
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                style = CodeTextStyle,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun DiffBlockView(block: MarkdownBlock.DiffBlock) {
    val scrollState = rememberScrollState()
    val colors = codeColors()
    val annotatedLines = rememberDiffLineAnnotatedStrings(block.lines, colors)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .horizontalScroll(scrollState)
                .padding(vertical = 8.dp),
    ) {
        block.lines.forEachIndexed { index, line ->
            val annotated = annotatedLines.getOrElse(index) { AnnotatedString(line.text) }
            val background =
                when (line.kind) {
                    DiffLineKind.Added -> openCodeColor("diff-add").copy(alpha = 0.18f)
                    DiffLineKind.Deleted -> openCodeColor("diff-delete").copy(alpha = 0.16f)
                    DiffLineKind.Header -> openCodeColor("syntax-diff-unknown").copy(alpha = 0.14f)
                    DiffLineKind.Context -> androidx.compose.ui.graphics.Color.Transparent
                }
            Box(
                modifier =
                    Modifier
                        .widthIn(min = 320.dp)
                        .background(background)
                        .padding(horizontal = 12.dp, vertical = 1.dp),
            ) {
                Text(
                    text = annotated,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                    style = CodeTextStyle,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TableView(
    block: MarkdownBlock.Table,
    onLinkTap: ((String) -> Unit)?,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
    ) {
        TableRowView(block.header, isHeader = true, onLinkTap = onLinkTap)
        block.rows.forEach { row ->
            TableRowView(row, isHeader = false, onLinkTap = onLinkTap)
        }
    }
}

@Composable
private fun TableRowView(
    row: dev.opencode.mobile.markdown.MarkdownTableRow,
    isHeader: Boolean,
    onLinkTap: ((String) -> Unit)?,
) {
    Row {
        row.cells.forEach { cell ->
            Box(
                modifier =
                    Modifier
                        .widthIn(min = 112.dp, max = 220.dp)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .background(if (isHeader) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                MarkdownInlineText(
                    inlines = cell.inlines,
                    style = if (isHeader) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.bodyMedium,
                    onLinkTap = onLinkTap,
                )
            }
        }
    }
}

@Composable
private fun MarkdownInlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onLinkTap: ((String) -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val colors = markdownTextColors()
    val annotated = rememberMarkdownAnnotatedString(inlines, colors)
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
        onClick = { offset ->
            val annotation = annotated.getStringAnnotations(tag = "url", start = offset, end = offset).firstOrNull()
            val url = annotation?.item ?: return@ClickableText
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (onLinkTap != null) {
                    onLinkTap(url)
                } else {
                    uriHandler.openUri(url)
                }
            }
        },
    )
}

private data class MarkdownTextColors(
    val codeText: androidx.compose.ui.graphics.Color,
    val codeBackground: androidx.compose.ui.graphics.Color,
    val emphasis: androidx.compose.ui.graphics.Color,
    val strong: androidx.compose.ui.graphics.Color,
    val link: androidx.compose.ui.graphics.Color,
    val image: androidx.compose.ui.graphics.Color,
)

@Composable
private fun markdownTextColors(): MarkdownTextColors =
    MarkdownTextColors(
        codeText = openCodeColor("markdown-code-text"),
        codeBackground = openCodeColor("markdown-code-bg"),
        emphasis = openCodeColor("markdown-em"),
        strong = openCodeColor("markdown-strong"),
        link = openCodeColor("markdown-link-text"),
        image = openCodeColor("markdown-image"),
    )

private fun buildMarkdownAnnotatedString(
    inlines: List<MarkdownInline>,
    colors: MarkdownTextColors,
): AnnotatedString =
    buildAnnotatedString {
        appendInlines(inlines, colors)
    }

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    colors: MarkdownTextColors,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)
            is MarkdownInline.Code ->
                withStyle(
                    SpanStyle(
                        color = colors.codeText,
                        background = colors.codeBackground,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) {
                    append(inline.code)
                }
            is MarkdownInline.Emphasis ->
                withStyle(SpanStyle(color = colors.emphasis, fontStyle = FontStyle.Italic)) {
                    appendInlines(inline.children, colors)
                }
            is MarkdownInline.Strong ->
                withStyle(SpanStyle(color = colors.strong, fontWeight = FontWeight.SemiBold)) {
                    appendInlines(inline.children, colors)
                }
            is MarkdownInline.Link -> {
                val start = length
                pushStringAnnotation(tag = "url", annotation = inline.destination)
                withStyle(
                    SpanStyle(
                        color = colors.link,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    appendInlines(inline.children, colors)
                }
                pop()
                if (length == start) append(inline.destination)
            }
            is MarkdownInline.ImageAlt ->
                withStyle(SpanStyle(color = colors.image, fontStyle = FontStyle.Italic)) {
                    append("[image: ${inline.alt}]")
                }
            MarkdownInline.SoftBreak -> append("\n")
            MarkdownInline.HardBreak -> append("\n")
        }
    }
}

internal fun buildCodeAnnotatedString(
    code: String,
    spans: List<CodeSpan>,
    colors: CodeColors,
): AnnotatedString =
    buildAnnotatedString {
        append(code)
        spans
            .sortedWith(
                compareBy<CodeSpan> { it.role.stylePriority() }
                    .thenByDescending { it.end - it.start }
                    .thenBy { it.start },
            )
            .forEach { span ->
                val start = span.start.coerceIn(0, code.length)
                val end = span.end.coerceIn(start, code.length)
                if (start < end) {
                    addStyle(SpanStyle(color = colors.colorForRole(span.role)), start, end)
                }
            }
    }

private fun CodeTokenRole.stylePriority(): Int =
    when (this) {
        CodeTokenRole.Punctuation -> 0
        CodeTokenRole.Operator -> 1
        CodeTokenRole.Plain -> 2
        CodeTokenRole.Variable -> 3
        CodeTokenRole.Property -> 4
        CodeTokenRole.Function -> 5
        CodeTokenRole.Number -> 6
        CodeTokenRole.Constant -> 7
        CodeTokenRole.String -> 8
        CodeTokenRole.Comment -> 9
        CodeTokenRole.Keyword -> 10
        CodeTokenRole.Type -> 11
        CodeTokenRole.Tag -> 12
        CodeTokenRole.Attribute -> 13
        CodeTokenRole.Inserted -> 14
        CodeTokenRole.Deleted -> 15
        CodeTokenRole.Warning -> 16
        CodeTokenRole.Critical -> 17
    }

internal data class CodeColors(
    val plain: androidx.compose.ui.graphics.Color,
    val comment: androidx.compose.ui.graphics.Color,
    val keyword: androidx.compose.ui.graphics.Color,
    val string: androidx.compose.ui.graphics.Color,
    val constant: androidx.compose.ui.graphics.Color,
    val type: androidx.compose.ui.graphics.Color,
    val property: androidx.compose.ui.graphics.Color,
    val primitive: androidx.compose.ui.graphics.Color,
    val info: androidx.compose.ui.graphics.Color,
    val inserted: androidx.compose.ui.graphics.Color,
    val deleted: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val critical: androidx.compose.ui.graphics.Color,
)

@Composable
private fun codeColors(): CodeColors =
    CodeColors(
        plain = MaterialTheme.colorScheme.onSurface,
        comment = openCodeColor("syntax-comment"),
        keyword = openCodeColor("syntax-keyword"),
        string = openCodeColor("syntax-string"),
        constant = openCodeColor("syntax-constant"),
        type = openCodeColor("syntax-type"),
        property = openCodeColor("syntax-property"),
        primitive = openCodeColor("syntax-primitive"),
        info = openCodeColor("syntax-info"),
        inserted = openCodeColor("diff-add"),
        deleted = openCodeColor("diff-delete"),
        warning = openCodeColor("syntax-diff-unknown"),
        critical = openCodeColor("syntax-critical"),
    )

private fun CodeColors.colorForRole(role: CodeTokenRole): androidx.compose.ui.graphics.Color =
    when (role) {
        CodeTokenRole.Plain -> plain
        CodeTokenRole.Comment -> comment
        CodeTokenRole.Keyword -> keyword
        CodeTokenRole.Type -> type
        CodeTokenRole.Variable -> plain
        CodeTokenRole.Property,
        CodeTokenRole.Constant,
        -> constant
        CodeTokenRole.Function -> primitive
        CodeTokenRole.Number,
        CodeTokenRole.Attribute,
        -> property
        CodeTokenRole.String -> string
        CodeTokenRole.Operator,
        CodeTokenRole.Punctuation,
        -> plain
        CodeTokenRole.Tag -> info
        CodeTokenRole.Inserted -> inserted
        CodeTokenRole.Deleted -> deleted
        CodeTokenRole.Warning -> warning
        CodeTokenRole.Critical -> critical
    }

@Composable
private fun headingStyle(level: Int): TextStyle =
    when (level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    }

@Composable
private fun headingColor(level: Int): androidx.compose.ui.graphics.Color =
    when (level) {
        1 -> openCodeColor("markdown-h1")
        2 -> openCodeColor("markdown-h2")
        else -> openCodeColor("markdown-h3")
    }

private fun blockSpacingFor(variant: OpenCodeRenderedMarkdownVariant): Dp =
    when (variant) {
        OpenCodeRenderedMarkdownVariant.AssistantMessage -> 8.dp
        OpenCodeRenderedMarkdownVariant.OutgoingMessage -> 8.dp
        OpenCodeRenderedMarkdownVariant.Standalone -> 12.dp
    }

@Composable
private fun rememberCodeAnnotatedString(
    code: String,
    spans: List<CodeSpan>,
    colors: CodeColors,
): AnnotatedString {
    var annotated by remember(code, spans) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(code, spans, colors) {
        annotated =
            withContext(Dispatchers.Default) {
                buildCodeAnnotatedString(code, spans, colors)
            }
    }
    return annotated ?: remember(code) { AnnotatedString(code) }
}

@Composable
private fun rememberDiffLineAnnotatedStrings(
    lines: List<dev.opencode.mobile.markdown.DiffLine>,
    colors: CodeColors,
): List<AnnotatedString> {
    var annotatedLines by remember(lines) { mutableStateOf<List<AnnotatedString>?>(null) }
    LaunchedEffect(lines, colors) {
        annotatedLines =
            withContext(Dispatchers.Default) {
                lines.map { line -> buildCodeAnnotatedString(line.text, line.spans, colors) }
            }
    }
    return annotatedLines
        ?: remember(lines) {
            lines.map { line -> AnnotatedString(line.text) }
        }
}

@Composable
private fun rememberMarkdownAnnotatedString(
    inlines: List<MarkdownInline>,
    colors: MarkdownTextColors,
): AnnotatedString {
    var annotated by remember(inlines) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(inlines, colors) {
        annotated =
            withContext(Dispatchers.Default) {
                buildMarkdownAnnotatedString(inlines, colors)
            }
    }
    return annotated ?: remember(inlines) { AnnotatedString("") }
}
