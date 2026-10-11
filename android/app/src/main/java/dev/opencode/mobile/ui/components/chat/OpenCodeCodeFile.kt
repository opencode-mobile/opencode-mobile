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

/** One source line with syntax spans relative to [text]. */
data class OpenCodeCodeLine(
    val text: String,
    val spans: List<CodeSpan> = emptyList(),
)

/** Splits a whole source plus whole-source spans into [OpenCodeCodeLine]s for [OpenCodeCodeFile]. */
object OpenCodeCodeLines {
    const val TAB_WIDTH = 4

    /**
     * `\n` and `\r\n` end lines; a single trailing newline does not add an empty last line. Tabs
     * expand to [TAB_WIDTH] spaces so the monospace width stays predictable. Spans that cross
     * lines are clipped per line.
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
            while (nextSpan < sorted.size && sorted[nextSpan].start < lineEnd) {
                active += sorted[nextSpan]
                nextSpan++
            }
            active.removeAll { it.end <= lineStart }
            val lineSpans =
                active.mapNotNull { span ->
                    val start = maxOf(span.start, lineStart) - lineStart
                    val end = minOf(span.end, lineEnd) - lineStart
                    if (start < end) CodeSpan(start, end, span.role) else null
                }
            lines += expandTabs(source.substring(lineStart, lineEnd), lineSpans)
            if (newline >= contentEnd) break
            lineStart = newline + 1
        }
        return lines
    }

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
    gutterWidth: androidx.compose.ui.unit.Dp,
    lineNumberColor: androidx.compose.ui.graphics.Color,
    gutterDivider: androidx.compose.ui.graphics.Color,
    wrapLines: Boolean,
    horizontalOffset: () -> Float,
) {
    val annotated = remember(line, colors) { buildCodeAnnotatedString(line.text, line.spans, colors) }
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
