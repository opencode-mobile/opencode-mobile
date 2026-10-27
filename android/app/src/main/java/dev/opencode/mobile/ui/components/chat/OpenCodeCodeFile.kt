package dev.opencode.mobile.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.roundToInt

private val CodeFileGutterPadding = 8.dp
private val CodeFileCodePadding = 12.dp
private val CodeFileLineMinHeight = 20.dp
private const val CODE_FILE_MIN_GUTTER_DIGITS = 2
private const val CODE_FILE_MARK_ALPHA = 0.28f

/** One source line with syntax spans relative to [text]. */
data class OpenCodeCodeLine(
    val text: String,
    val spans: List<CodeSpan> = emptyList(),
    /** Ranges of [text] drawn on the marked background, such as where two highlighters disagree. */
    val marks: List<OpenCodeCodeMark> = emptyList(),
)

/** A marked range of one [OpenCodeCodeLine], [start] inclusive and [end] exclusive. */
data class OpenCodeCodeMark(
    val start: Int,
    val end: Int,
)

/** Splits a whole source plus whole-source spans into [OpenCodeCodeLine]s for [OpenCodeCodeFile]. */
object OpenCodeCodeLines {
    const val TAB_WIDTH = 4

    /**
     * Longer lines are cut for display and end with [CUT_MARKER], so a minified or single-line
     * file cannot outgrow the layout width limit.
     */
    const val MAX_DISPLAY_LINE_LENGTH = 5_000

    const val CUT_MARKER = " …"

    /**
     * `\n` and `\r\n` end lines; a single trailing newline does not add an empty last line. Tabs
     * expand to [TAB_WIDTH] spaces so the monospace width stays predictable. Spans that cross
     * lines are clipped per line, and to the cut of a line past [MAX_DISPLAY_LINE_LENGTH].
     */
    fun split(
        source: String,
        spans: List<CodeSpan> = emptyList(),
    ): List<OpenCodeCodeLine> {
        if (source.isEmpty()) return listOf(OpenCodeCodeLine(""))
        val sorted = spans.filter { it.end > it.start }.sortedBy { it.start }
        val active = ArrayList<CodeSpan>()
        var nextSpan = 0
        val lines = ArrayList<OpenCodeCodeLine>()
        var lineStart = 0
        val contentEnd = if (source.endsWith('\n')) source.length - 1 else source.length
        while (lineStart <= contentEnd) {
            val newline = source.indexOf('\n', lineStart).let { if (it < 0 || it > contentEnd) contentEnd else it }
            var lineEnd = newline
            if (lineEnd > lineStart && source[lineEnd - 1] == '\r') lineEnd--
            // Expanded tabs only grow a line, so cutting the raw line first bounds the work too.
            val shownEnd =
                if (lineEnd - lineStart > MAX_DISPLAY_LINE_LENGTH) {
                    safeCut(source, lineStart + MAX_DISPLAY_LINE_LENGTH)
                } else {
                    lineEnd
                }
            while (nextSpan < sorted.size && sorted[nextSpan].start < lineEnd) {
                active += sorted[nextSpan]
                nextSpan++
            }
            active.removeAll { it.end <= lineStart }
            val lineSpans =
                active.mapNotNull { span ->
                    val start = maxOf(span.start, lineStart) - lineStart
                    val end = minOf(span.end, shownEnd) - lineStart
                    if (start < end) CodeSpan(start, end, span.role) else null
                }
            lines += displayLine(source.substring(lineStart, shownEnd), lineSpans, cut = shownEnd < lineEnd)
            if (newline >= contentEnd) break
            lineStart = newline + 1
        }
        return lines
    }

    private fun displayLine(
        text: String,
        spans: List<CodeSpan>,
        cut: Boolean,
    ): OpenCodeCodeLine {
        val expanded = expandTabs(text, spans)
        if (expanded.text.length <= MAX_DISPLAY_LINE_LENGTH) {
            return if (cut) expanded.copy(text = expanded.text + CUT_MARKER) else expanded
        }
        val end = safeCut(expanded.text, MAX_DISPLAY_LINE_LENGTH)
        return OpenCodeCodeLine(
            text = expanded.text.substring(0, end) + CUT_MARKER,
            spans =
                expanded.spans.mapNotNull { span ->
                    if (span.start < end) CodeSpan(span.start, minOf(span.end, end), span.role) else null
                },
        )
    }

    /** [index] moved back one so a cut never splits a surrogate pair. */
    private fun safeCut(
        text: String,
        index: Int,
    ): Int = if (index in 1 until text.length && text[index - 1].isHighSurrogate()) index - 1 else index

    private fun expandTabs(
        text: String,
        spans: List<CodeSpan>,
    ): OpenCodeCodeLine {
        if ('\t' !in text) return OpenCodeCodeLine(text, spans)
        val map = IntArray(text.length + 1)
        val expanded = StringBuilder(text.length + TAB_WIDTH * 2)
        text.forEachIndexed { index, char ->
            map[index] = expanded.length
            if (char == '\t') repeat(TAB_WIDTH) { expanded.append(' ') } else expanded.append(char)
        }
        map[text.length] = expanded.length
        return OpenCodeCodeLine(
            text = expanded.toString(),
            spans = spans.map { CodeSpan(map[it.start], map[it.end], it.role) },
        )
    }
}

/**
 * Read-only code file: line-number gutter plus syntax-highlighted lines. Long lines scroll
 * horizontally together (the gutter stays put) unless [wrapLines] is on. Lazily composed, so it
 * fills the height it is given and must not sit inside another vertical scroller.
 */
@Composable
fun OpenCodeCodeFile(
    lines: List<OpenCodeCodeLine>,
    modifier: Modifier = Modifier,
    wrapLines: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    state: LazyListState = rememberLazyListState(),
) {
    val colors = codeColors()
    val markColor = openCodeColor("warning").copy(alpha = CODE_FILE_MARK_ALPHA)
    val lineNumberColor = openCodeColor("text-weaker")
    val gutterDivider = openCodeColor("border-weaker-base")
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val charWidthPx =
        remember(textMeasurer, density) {
            textMeasurer
                .measure("0", CodeTextStyle)
                .size.width
                .toFloat()
        }
    val digits = maxOf(lines.size.toString().length, CODE_FILE_MIN_GUTTER_DIGITS)
    val gutterWidth = with(density) { (charWidthPx * digits).toDp() } + CodeFileGutterPadding * 2
    val longestLine = remember(lines) { lines.maxOfOrNull { it.text.length } ?: 0 }

    // Code reads left to right regardless of the app's layout direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier.clipToBounds()) {
            val codeViewportPx =
                constraints.maxWidth - with(density) { (gutterWidth + CodeFileCodePadding * 2).toPx() }
            val maxOffset = if (wrapLines) 0f else (longestLine * charWidthPx - codeViewportPx).coerceAtLeast(0f)
            var horizontalOffset by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(maxOffset) { horizontalOffset = horizontalOffset.coerceIn(0f, maxOffset) }
            val scrollableState =
                rememberScrollableState { delta ->
                    val previous = horizontalOffset
                    horizontalOffset = (previous - delta).coerceIn(0f, maxOffset)
                    previous - horizontalOffset
                }
            LazyColumn(
                state = state,
                contentPadding = contentPadding,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (maxOffset > 0f) {
                                Modifier.scrollable(scrollableState, Orientation.Horizontal)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                itemsIndexed(lines) { index, line ->
                    CodeFileRow(
                        number = index + 1,
                        line = line,
                        colors = colors,
                        markColor = markColor,
                        gutterWidth = gutterWidth,
                        lineNumberColor = lineNumberColor,
                        gutterDivider = gutterDivider,
                        wrapLines = wrapLines,
                        horizontalOffset = { horizontalOffset },
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeFileRow(
    number: Int,
    line: OpenCodeCodeLine,
    colors: CodeColors,
    markColor: androidx.compose.ui.graphics.Color,
    gutterWidth: androidx.compose.ui.unit.Dp,
    lineNumberColor: androidx.compose.ui.graphics.Color,
    gutterDivider: androidx.compose.ui.graphics.Color,
    wrapLines: Boolean,
    horizontalOffset: () -> Float,
) {
    val annotated =
        remember(line, colors, markColor) {
            val highlighted = buildCodeAnnotatedString(line.text, line.spans, colors)
            if (line.marks.isEmpty()) {
                highlighted
            } else {
                AnnotatedString
                    .Builder(highlighted)
                    .apply {
                        line.marks.forEach { mark ->
                            val start = mark.start.coerceIn(0, line.text.length)
                            val end = mark.end.coerceIn(start, line.text.length)
                            if (start < end) addStyle(SpanStyle(background = markColor), start, end)
                        }
                    }.toAnnotatedString()
            }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = CodeFileLineMinHeight)
                .semantics(mergeDescendants = true) { contentDescription = "Line $number, ${line.text}" },
    ) {
        Text(
            text = number.toString(),
            modifier =
                Modifier
                    .width(gutterWidth)
                    .fillMaxHeight()
                    .drawBehind {
                        val x = size.width - 1.dp.toPx() / 2
                        drawLine(gutterDivider, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }.padding(horizontal = CodeFileGutterPadding),
            color = lineNumberColor,
            style = CodeTextStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = CodeFileCodePadding)
                    .clipToBounds(),
        ) {
            if (wrapLines) {
                Text(
                    text = annotated,
                    modifier = Modifier.padding(end = CodeFileCodePadding),
                    color = colors.plain,
                    style = CodeTextStyle,
                    softWrap = true,
                )
            } else {
                Text(
                    text = annotated,
                    modifier =
                        Modifier
                            .wrapContentWidth(align = Alignment.Start, unbounded = true)
                            .offset { IntOffset(-horizontalOffset().roundToInt(), 0) },
                    color = colors.plain,
                    style = CodeTextStyle,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 240)
@Composable
private fun OpenCodeCodeFilePreview() {
    OpenCodeTheme(darkTheme = false) {
        val source = "fun main() {\n\tprintln(\"Hello\") // greet\n}\n"
        OpenCodeCodeFile(
            lines =
                OpenCodeCodeLines.split(
                    source,
                    listOf(
                        CodeSpan(0, 3, CodeTokenRole.Keyword),
                        CodeSpan(22, 29, CodeTokenRole.String),
                        CodeSpan(31, 39, CodeTokenRole.Comment),
                    ),
                ),
        )
    }
}
