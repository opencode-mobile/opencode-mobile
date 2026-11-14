package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import dev.opencode.hexcode.designsystem.HexTheme

/** Marks inline code in an [AnnotatedString] for [HexCodeText] to box (`.md-c`). */
const val HEX_CODE_TAG = "hex.code"

/** Inline code's own look (`.md-c`): the system monospace at 0.82 of the size, regular, `text1` at 88 %. */
@Composable
fun hexCodeStyle(): SpanStyle =
    SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = CODE_SIZE.em,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.01).em,
        color = HexTheme.colors.text1.copy(alpha = 0.88f),
    )

/**
 * Text whose inline code (ranges annotated [HEX_CODE_TAG], styled with [hexCodeStyle]) sits in a faint box that hugs
 * the glyphs, not the whole line, as Slack draws it but quieter: `tint(4.5)` fill, a hairline `tint(7)` edge, radius
 * 0.26 em and 0.26 em either side. Code that wraps gets a box on each line; boxes on neighbouring lines never touch.
 */
@Composable
fun HexCodeText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /** Paints the text instead of [color] (a light sweeping across it); spans with their own colour keep it. */
    brush: Brush? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fill = HexTheme.colors.tint(4.5f)
    val edge = HexTheme.colors.tint(7f)
    val ranges = remember(text) { text.getStringAnnotations(HEX_CODE_TAG, 0, text.length) }
    val spoken = remember(text) { text.text.replace("\u2005", "").replace("\u2060", "") }
    val drawn =
        modifier
            // Screen readers get the plain words, without the spaces and joiners added for the boxes.
            .clearAndSetSemantics { this.text = AnnotatedString(spoken) }
            .drawBehind {
                val result = layout ?: return@drawBehind
                if (result.layoutInput.text != text) return@drawBehind
                val em = style.fontSize.toPx()
                ranges.forEach { drawCodeBoxes(result, it.start, it.end, em, fill, edge) }
            }
    if (brush != null) {
        // A brush only paints through BasicText: Text's colour would cover it.
        BasicText(text, drawn, style.copy(brush = brush), onTextLayout = { layout = it }, overflow = overflow, maxLines = maxLines)
    } else {
        Text(text, drawn, color = color, maxLines = maxLines, overflow = overflow, onTextLayout = { layout = it }, style = style)
    }
}

/** One rounded box per line the code range [start] to [end] covers, around its glyphs. */
private fun DrawScope.drawCodeBoxes(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    em: Float,
    fill: Color,
    edge: Color,
) {
    if (end <= start) return
    val pad = PAD * em
    val radius = CornerRadius(RADIUS * em)
    val code = CODE_SIZE * em
    val hairline = 1f
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset(end - 1)
    for (line in first..last) {
        if (line >= layout.lineCount) break
        val from = maxOf(start, layout.getLineStart(line))
        // Leave out a space the line broke at.
        var to = minOf(end, layout.getLineEnd(line, visibleEnd = true))
        while (to > from && layout.layoutInput.text[to - 1].isWhitespace()) to--
        if (to <= from) continue
        val left = layout.getBoundingBox(from).left - pad
        val right = layout.getBoundingBox(to - 1).right + pad
        val baseline = layout.getLineBaseline(line)
        // The monospace's ascent and descent at its size, plus a little: the box hugs the glyphs.
        val top = baseline - code * ASCENT
        val bottom = baseline + code * DESCENT
        val size = Size(right - left, bottom - top)
        drawRoundRect(fill, Offset(left, top), size, radius)
        drawRoundRect(
            edge,
            Offset(left + hairline / 2, top + hairline / 2),
            Size(size.width - hairline, size.height - hairline),
            radius,
            style = Stroke(hairline),
        )
    }
}

private const val CODE_SIZE = 0.82f
private const val PAD = 0.26f
private const val RADIUS = 0.26f
private const val ASCENT = 0.92f
private const val DESCENT = 0.28f
