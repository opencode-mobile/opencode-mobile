package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import dev.opencode.mobile.markdown.MarkdownTableRow
import dev.opencode.mobile.markdown.OpenCodeMarkdownDetector
import dev.opencode.mobile.markdown.isLikelyExpandable
import dev.opencode.mobile.markdown.plainTextFromMarkdownInlines
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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

private val CodeBlockShape = RoundedCornerShape(6.dp)

/** Defer only large *inline* attributed builds; fenced code/diff paint spans sync. */
private const val BACKGROUND_ANNOTATION_CHARACTER_THRESHOLD = 2_000

/**
 * Format-always / color-later: never strip prepared spans while streaming.
 * Structure-only docs already have empty spans; highlight-core colors arrive later.
 */
@Suppress("UNUSED_PARAMETER")
internal fun forcePlainStreamingCode(
    isStreaming: Boolean,
    codeLength: Int,
): Boolean = false

@Composable
fun OpenCodeRenderedMarkdown(
    document: MarkdownDocument,
    variant: OpenCodeRenderedMarkdownVariant,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    initialHeight: Dp = 1.dp,
    collapsedMaxHeight: Dp? = null,
    expanded: Boolean = true,
    onLinkTap: ((String) -> Unit)? = null,
    onTextTap: ((OpenCodeMessageTapTarget) -> Unit)? = null,
    onExpandableChange: (Boolean) -> Unit = {},
    onContentHeightChange: (Dp) -> Unit = {},
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val tapGate = remember { MessageTapGate() }
    tapGate.onLinkTap = onLinkTap
    tapGate.fallbackOpen = { url -> runCatching { uriHandler.openUri(url) } }
    val trackLinkTaps = onTextTap != null && variant != OpenCodeRenderedMarkdownVariant.OutgoingMessage
    tapGate.track = trackLinkTaps
    val linkTap = remember { { url: String -> tapGate.onLink(url) } }
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

    val blocks = @Composable {
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
                    isStreaming = isStreaming,
                    onLinkTap = if (trackLinkTaps || onLinkTap != null) linkTap else null,
                    tapGate = tapGate.takeIf { trackLinkTaps },
                )
            }
        }
    }
    if (variant == OpenCodeRenderedMarkdownVariant.OutgoingMessage) {
        blocks()
    } else {
        SelectionContainer(
            modifier = Modifier.opensMessageActionsOnTextTap(onTextTap, tapGate),
        ) {
            blocks()
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    variant: OpenCodeRenderedMarkdownVariant,
    isStreaming: Boolean,
    onLinkTap: ((String) -> Unit)?,
    tapGate: MessageTapGate?,
) {
    when (block) {
        is MarkdownBlock.Paragraph ->
            MarkdownInlineText(
                inlines = block.inlines,
                style = MaterialTheme.typography.bodyLarge,
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
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(openCodeColor("markdown-quote-bar"), RoundedCornerShape(2.dp)),
                )
                MarkdownInlineText(
                    inlines = block.inlines,
                    style = MaterialTheme.typography.bodyLarge,
                    color = openCodeColor("markdown-quote-text"),
                    onLinkTap = onLinkTap,
                    modifier = Modifier.weight(1f),
                )
            }
        is MarkdownBlock.BulletList ->
            MarkdownList(
                markers = List(block.items.size) { "•" },
                items = block.items.map { it.inlines },
                onLinkTap = onLinkTap,
            )
        is MarkdownBlock.OrderedList ->
            MarkdownList(
                markers = List(block.items.size) { index -> "${block.start + index}." },
                items = block.items.map { it.inlines },
                onLinkTap = onLinkTap,
            )
        is MarkdownBlock.CodeBlock -> CodeBlockView(block, isStreaming = isStreaming, tapGate = tapGate)
        is MarkdownBlock.DiffBlock -> DiffBlockView(block, tapGate = tapGate)
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
private fun MarkdownList(
    markers: List<String>,
    items: List<List<MarkdownInline>>,
    onLinkTap: ((String) -> Unit)?,
) {
    val style = MaterialTheme.typography.bodyLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val markerWidth =
        remember(markers, style, density) {
            if (markers.isEmpty()) {
                16.dp
            } else {
                val maxPx =
                    markers.maxOf { marker ->
                        textMeasurer.measure(text = AnnotatedString(marker), style = style).size.width
                    }
                with(density) { maxPx.toDp() }.coerceAtLeast(16.dp)
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markers.forEachIndexed { index, marker ->
            ListRow(
                marker = marker,
                markerWidth = markerWidth,
                inlines = items[index],
                onLinkTap = onLinkTap,
            )
        }
    }
}

@Composable
private fun ListRow(
    marker: String,
    markerWidth: Dp,
    inlines: List<MarkdownInline>,
    onLinkTap: ((String) -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge,
            color = openCodeColor("markdown-list-marker"),
            textAlign = TextAlign.End,
            modifier = Modifier.width(markerWidth),
        )
        MarkdownInlineText(
            inlines = inlines,
            style = MaterialTheme.typography.bodyLarge,
            onLinkTap = onLinkTap,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlockView(
    block: MarkdownBlock.CodeBlock,
    isStreaming: Boolean,
    tapGate: MessageTapGate?,
) {
    val scrollState = rememberScrollState()
    val colors = codeColors()
    val annotated =
        rememberCodeAnnotatedString(
            code = block.code,
            spans = block.spans,
            colors = colors,
            forcePlain = forcePlainStreamingCode(isStreaming, block.code.length),
        )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface(MaterialTheme.colorScheme.surfaceVariant), CodeBlockShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CodeBlockShape)
                .messageCodeBlockTapTarget(block.code, tapGate)
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = annotated,
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
            color = colors.plain,
            style = CodeTextStyle,
            softWrap = false,
        )
    }
}

@Composable
private fun DiffBlockView(
    block: MarkdownBlock.DiffBlock,
    tapGate: MessageTapGate?,
) {
    val scrollState = rememberScrollState()
    val colors = codeColors()
    val annotatedLines = rememberDiffLineAnnotatedStrings(block.lines, colors)
    val code = remember(block.lines) { block.lines.joinToString("\n") { it.text } }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.surface(MaterialTheme.colorScheme.surfaceVariant), CodeBlockShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CodeBlockShape)
                .messageCodeBlockTapTarget(code, tapGate)
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
                    color = colors.plain,
                    style = CodeTextStyle,
                    softWrap = false,
                )
            }
        }
    }
}

private val TableCellMinWidth = 112.dp
private val TableCellHorizontalPadding = 10.dp

@Composable
private fun TableView(
    block: MarkdownBlock.Table,
    onLinkTap: ((String) -> Unit)?,
) {
    val allRows = remember(block) { listOf(block.header) + block.rows }
    val columnCount = remember(allRows) { allRows.maxOfOrNull { it.cells.size } ?: 0 }
    val style = MaterialTheme.typography.bodyMedium
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val preferredWidths =
        remember(allRows, columnCount, style, density) {
            if (columnCount == 0) {
                emptyList()
            } else {
                val horizontalPaddingPx = with(density) { (TableCellHorizontalPadding * 2).roundToPx() }
                val minPx = with(density) { TableCellMinWidth.roundToPx() }
                List(columnCount) { column ->
                    val contentPx =
                        allRows.maxOf { row ->
                            val inlines =
                                row.cells
                                    .getOrNull(column)
                                    ?.inlines
                                    .orEmpty()
                            val plain = plainTextFromMarkdownInlines(inlines)
                            textMeasurer.measure(text = AnnotatedString(plain), style = style).size.width
                        }
                    with(density) {
                        maxOf(minPx, contentPx + horizontalPaddingPx).toDp()
                    }
                }
            }
        }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth
        val preferredTotal = preferredWidths.fold(0.dp) { acc, width -> acc + width }
        val columnWidths =
            if (preferredWidths.isEmpty() || preferredTotal >= available) {
                preferredWidths
            } else {
                val share = (available - preferredTotal) / preferredWidths.size
                preferredWidths.map { it + share }
            }
        val needsScroll = preferredTotal > available
        val scrollState = rememberScrollState()
        // Avoid IntrinsicSize here: nested intrinsic measurement inside LazyList is
        // far more expensive than a one-row equal-height Layout during streaming.
        Column(
            modifier =
                Modifier
                    .then(if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier)
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        ) {
            TableRowView(block.header, columnWidths = columnWidths, isHeader = true, onLinkTap = onLinkTap)
            block.rows.forEach { row ->
                TableRowView(row, columnWidths = columnWidths, isHeader = false, onLinkTap = onLinkTap)
            }
        }
    }
}

@Composable
private fun TableRowView(
    row: MarkdownTableRow,
    columnWidths: List<Dp>,
    isHeader: Boolean,
    onLinkTap: ((String) -> Unit)?,
) {
    EqualHeightRow {
        columnWidths.forEachIndexed { index, width ->
            val inlines =
                row.cells
                    .getOrNull(index)
                    ?.inlines
                    .orEmpty()
            Box(
                modifier =
                    Modifier
                        .width(width)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .background(
                            if (isHeader) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ).padding(horizontal = TableCellHorizontalPadding, vertical = 8.dp),
            ) {
                MarkdownInlineText(
                    inlines = inlines,
                    style =
                        if (isHeader) {
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    onLinkTap = onLinkTap,
                )
            }
        }
    }
}

/**
 * Equal-height cells without [IntrinsicSize] (costly in LazyList) and without
 * measuring the same [Measurable] twice (illegal in Compose Layout).
 * Two [SubcomposeLayout] slots: probe natural heights, then remasure to max.
 */
@Composable
private fun EqualHeightRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier = modifier) { constraints ->
        val loose = constraints.copy(minHeight = 0)
        val firstPass =
            subcompose(EqualHeightRowSlot.Probe) { content() }.map { it.measure(loose) }
        val rowHeight =
            firstPass
                .maxOfOrNull { it.height }
                ?.coerceIn(constraints.minHeight, constraints.maxHeight)
                ?: 0
        val placeables =
            subcompose(EqualHeightRowSlot.Final) { content() }.mapIndexed { index, measurable ->
                val first = firstPass[index]
                measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(
                        width = first.width,
                        height = rowHeight,
                    ),
                )
            }
        val width =
            placeables
                .sumOf { it.width }
                .coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, rowHeight) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width
            }
        }
    }
}

/** What a tap on assistant text landed on. */
sealed interface OpenCodeMessageTapTarget {
    data object Text : OpenCodeMessageTapTarget

    data class CodeBlock(
        val code: String,
    ) : OpenCodeMessageTapTarget
}

/**
 * Whether the user is dragging or flinging the list that holds the message.
 * A touch that lands then only stops the scroll, so it never opens message
 * actions.
 */
val LocalOpenCodeUserScrollInProgress = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * Decides, one finished touch at a time, whether a touch on message text is a
 * tap that opens message actions. Long-press belongs to text selection, and
 * the first tap after a selection only dismisses it.
 */
internal class MessageTextTapClassifier {
    private var selectionActive = false

    fun isTap(
        released: Boolean,
        moved: Boolean,
        linkHandled: Boolean,
        startedDuringScroll: Boolean,
    ): Boolean {
        if (linkHandled) {
            selectionActive = false
            return false
        }
        if (moved) return false
        if (!released) {
            selectionActive = true
            return false
        }
        if (selectionActive) {
            selectionActive = false
            return false
        }
        return !startedDuringScroll
    }
}

private class MessageTapGate {
    var onLinkTap: ((String) -> Unit)? = null
    var fallbackOpen: ((String) -> Unit)? = null
    var track: Boolean = false
    var handled: Boolean = false

    /** Set by the code block the current touch started on. */
    var codeBlock: String? = null
    var codeBlockRipple: (() -> Unit)? = null

    fun onLink(url: String) {
        if (track) handled = true
        val custom = onLinkTap
        if (custom != null) custom(url) else fallbackOpen?.invoke(url)
    }
}

/**
 * A short tap opens message actions. The pointer is not consumed, so a
 * long-press still starts text selection and a drag still scrolls. A touch
 * that moves past touch slop or lands while the list is scrolling is not a
 * tap. A link marks
 * [MessageTapGate.handled] and a code block sets [MessageTapGate.codeBlock]
 * before this sees the release.
 */
@Composable
internal fun Modifier.opensMessageActionsOnTextTap(onTextTap: ((OpenCodeMessageTapTarget) -> Unit)?): Modifier =
    opensMessageActionsOnTextTap(onTextTap, tapGate = null)

@Composable
private fun Modifier.opensMessageActionsOnTextTap(
    onTextTap: ((OpenCodeMessageTapTarget) -> Unit)?,
    tapGate: MessageTapGate?,
): Modifier {
    // Read `.value` inside the gesture. `by` would capture a stale local, and
    // keying pointerInput on the lambda restarts it whenever streaming recomposes.
    val onTextTapState = rememberUpdatedState(onTextTap)
    val userScrollInProgress = rememberUpdatedState(LocalOpenCodeUserScrollInProgress.current)
    if (onTextTap == null) return this
    return pointerInput(Unit) {
        val classifier = MessageTextTapClassifier()
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            // The parent sees Initial first, so this clears before a code block sets it.
            tapGate?.codeBlock = null
            tapGate?.codeBlockRipple = null
            val startedDuringScroll = userScrollInProgress.value()
            val slop = viewConfiguration.touchSlop
            val slopSquared = slop * slop
            val timeout = viewConfiguration.longPressTimeoutMillis
            val start = System.nanoTime()
            var moved = false
            var released = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if ((System.nanoTime() - start) / 1_000_000 >= timeout) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                // A scroll only takes movement past this same slop, and positions are
                // local, so a list or code block scroll also shows up here.
                if (dx * dx + dy * dy > slopSquared) {
                    moved = true
                    break
                }
                if (!change.pressed) {
                    released = true
                    break
                }
            }
            val linkHandled = tapGate?.handled == true
            tapGate?.handled = false
            val isTap =
                classifier.isTap(
                    released = released,
                    moved = moved,
                    linkHandled = linkHandled,
                    startedDuringScroll = startedDuringScroll,
                )
            if (!isTap) return@awaitEachGesture
            val code = tapGate?.codeBlock
            if (code != null) {
                tapGate.codeBlockRipple?.invoke()
                onTextTapState.value?.invoke(OpenCodeMessageTapTarget.CodeBlock(code))
            } else {
                onTextTapState.value?.invoke(OpenCodeMessageTapTarget.Text)
            }
        }
    }
}

/**
 * Marks a touch that starts on this block so a tap copies the block, and
 * plays the ripple once the message decides the touch was a tap.
 */
@Composable
private fun Modifier.messageCodeBlockTapTarget(
    code: String,
    tapGate: MessageTapGate?,
): Modifier {
    if (tapGate == null) return this
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val currentCode = rememberUpdatedState(code)
    return clip(CodeBlockShape)
        .indication(interactionSource, ripple())
        .pointerInput(tapGate) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                tapGate.codeBlock = currentCode.value
                tapGate.codeBlockRipple = {
                    scope.launch {
                        val press = PressInteraction.Press(down.position)
                        interactionSource.emit(press)
                        interactionSource.emit(PressInteraction.Release(press))
                    }
                }
            }
        }
}

private enum class EqualHeightRowSlot {
    Probe,
    Final,
}

@Composable
private fun MarkdownInlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onLinkTap: ((String) -> Unit)?,
) {
    val colors = markdownTextColors()
    // LinkAnnotation keeps link taps inside the document selection scope.
    val annotated = rememberMarkdownAnnotatedString(inlines, colors, onLinkTap)
    Text(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
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
    onLinkTap: ((String) -> Unit)?,
): AnnotatedString =
    buildAnnotatedString {
        appendInlines(inlines, colors, onLinkTap)
    }

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    colors: MarkdownTextColors,
    onLinkTap: ((String) -> Unit)?,
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
                        fontWeight = FontWeight.Medium,
                    ),
                ) {
                    // Native attributed backgrounds have no CSS padding; side spaces
                    // approximate desktop `padding: 2px 4px` horizontal inset.
                    append('\u2009')
                    append(inline.code)
                    append('\u2009')
                }
            is MarkdownInline.Emphasis ->
                withStyle(SpanStyle(color = colors.emphasis, fontStyle = FontStyle.Italic)) {
                    appendInlines(inline.children, colors, onLinkTap)
                }
            is MarkdownInline.Strong ->
                withStyle(SpanStyle(color = colors.strong, fontWeight = FontWeight.SemiBold)) {
                    appendInlines(inline.children, colors, onLinkTap)
                }
            is MarkdownInline.Link -> {
                val start = length
                val destination = inline.destination
                val openable =
                    destination.startsWith("http://") || destination.startsWith("https://")
                val linkStyle =
                    SpanStyle(
                        color = colors.link,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    )
                if (openable) {
                    pushLink(
                        LinkAnnotation.Url(
                            url = destination,
                            styles = TextLinkStyles(style = linkStyle),
                            linkInteractionListener =
                                onLinkTap?.let { tap ->
                                    LinkInteractionListener { tap(destination) }
                                },
                        ),
                    )
                    appendInlines(inline.children, colors, onLinkTap)
                    pop()
                } else {
                    withStyle(linkStyle) {
                        appendInlines(inline.children, colors, onLinkTap)
                    }
                }
                if (length == start) append(destination)
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
            ).forEach { span ->
                val start = span.start.coerceIn(0, code.length)
                val end = span.end.coerceIn(start, code.length)
                if (start < end) {
                    addStyle(SpanStyle(color = colors.colorFor(span.role)), start, end)
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

@Composable
private fun headingStyle(level: Int): TextStyle =
    when (level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
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
    forcePlain: Boolean,
): AnnotatedString {
    // Spans are prepared in the pipeline — paint on first frame.
    // Format-always: forcePlain is unused (always false).
    if (forcePlain) return remember(code) { AnnotatedString(code) }
    return remember(code, spans, colors) { buildCodeAnnotatedString(code, spans, colors) }
}

@Composable
private fun rememberDiffLineAnnotatedStrings(
    lines: List<dev.opencode.mobile.markdown.DiffLine>,
    colors: CodeColors,
): List<AnnotatedString> =
    remember(lines, colors) {
        lines.map { line -> buildCodeAnnotatedString(line.text, line.spans, colors) }
    }

@Composable
private fun rememberMarkdownAnnotatedString(
    inlines: List<MarkdownInline>,
    colors: MarkdownTextColors,
    onLinkTap: ((String) -> Unit)?,
): AnnotatedString {
    val plainText = remember(inlines) { plainTextFromMarkdownInlines(inlines) }
    if (plainText.length < BACKGROUND_ANNOTATION_CHARACTER_THRESHOLD) {
        return remember(inlines, colors, onLinkTap) {
            buildMarkdownAnnotatedString(inlines, colors, onLinkTap)
        }
    }
    var annotated by remember(inlines, colors, onLinkTap) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(inlines, colors, onLinkTap) {
        annotated =
            withContext(Dispatchers.Default) {
                buildMarkdownAnnotatedString(inlines, colors, onLinkTap)
            }
    }
    return annotated ?: remember(plainText) { AnnotatedString(plainText) }
}
