package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.designsystem.HexTheme

/** One chunk of a draft, as [HexChunk] and [HexChunkRow] show it. */
data class HexChunkItem(
    val text: String,
    val kind: HexChunkKind,
)

/** Where a chunk is. */
enum class HexChunkKind {
    /** Its take is still recording ("Listening…"). */
    Listening,

    /** The phone is turning it into text ("Transcribing…"). */
    Transcribing,

    /** It has its text, and can be tapped. */
    Text,
}

/** A chunk row's look. */
enum class HexChunkRowState { Normal, Selected, Dim, Past }

/**
 * The draft as running text (`.fx-draft`, `.chunk`): each chunk with text is tappable, on a `tint(4.5)` fill; the
 * [selected] one gets `tint(15)` and a `tint(28)` inner border, and the others dim. While [recording], earlier chunks
 * are quieter and not tappable. A [hint] ("Text appears when you pause") sits under the text. Three lines of room are
 * kept, so the draft never jumps as it fills.
 */
@Composable
fun HexChunk(
    chunks: List<HexChunkItem>,
    selected: Int?,
    recording: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    maxLines: Int = 8,
    /** `draft`, or a smaller size at the draft's weight as a long draft grows (app-plan.md, Long drafts). */
    style: TextStyle = HexTheme.type.draft,
) {
    val colors = HexTheme.colors
    // Where each tappable chunk sits in the text, and how it is filled.
    val ranges = mutableListOf<Triple<IntRange, Color, Color?>>()
    val text =
        buildAnnotatedString {
            chunks.forEachIndexed { index, chunk ->
                if (index > 0) append(" ")
                when (chunk.kind) {
                    HexChunkKind.Listening -> withStyle(SpanStyle(color = colors.text3)) { append(chunk.text) }
                    HexChunkKind.Transcribing -> withStyle(SpanStyle(color = colors.text4)) { append(chunk.text) }
                    HexChunkKind.Text ->
                        when {
                            recording -> withStyle(SpanStyle(color = colors.text2)) { append(chunk.text) }
                            selected != null && selected != index -> withStyle(SpanStyle(color = colors.text3)) { append(chunk.text) }
                            else -> {
                                val start = length
                                withLink(
                                    LinkAnnotation.Clickable(
                                        "chunk-$index",
                                        TextLinkStyles(SpanStyle(color = colors.text1)),
                                    ) { onSelect(index) },
                                ) { append(chunk.text) }
                                val chosen = selected == index
                                ranges +=
                                    Triple(
                                        start until length,
                                        colors.tint(if (chosen) 15f else 4.5f),
                                        if (chosen) colors.tint(28f) else null,
                                    )
                            }
                        }
                }
            }
        }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val room = with(LocalDensity.current) { (style.fontSize.value * style.lineHeight.value * 3).sp.toDp() }
    Column(modifier.heightIn(min = room)) {
        Text(
            text,
            style = style,
            color = colors.text1,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier =
                Modifier.drawBehind {
                    val result = layout ?: return@drawBehind
                    val padX = 3.dp.toPx()
                    val padY = 1.dp.toPx()
                    val radius = CornerRadius(6.dp.toPx())
                    val border = 1.dp.toPx()
                    ranges.forEach { (range, fill, edge) ->
                        if (range.isEmpty() || range.last >= result.layoutInput.text.length) return@forEach
                        val first = result.getLineForOffset(range.first)
                        val last = result.getLineForOffset(range.last)
                        for (line in first..last) {
                            if (line >= result.lineCount) break
                            val left = if (line == first) result.getHorizontalPosition(range.first, true) else result.getLineLeft(line)
                            val right = if (line == last) result.getHorizontalPosition(range.last + 1, true) else result.getLineRight(line)
                            // The fill covers the letters, not the line's leading, so lines of chunks stay apart.
                            val leading = (result.getLineBottom(line) - result.getLineTop(line) - style.fontSize.toPx() * 1.2f) / 2
                            val top = result.getLineTop(line) + leading.coerceAtLeast(0f) - padY
                            val bottom = result.getLineBottom(line) - leading.coerceAtLeast(0f) + padY
                            val topLeft = Offset(left - padX, top)
                            val box = Size(right - left + padX * 2, bottom - top)
                            drawRoundRect(fill, topLeft, box, radius)
                            if (edge != null) {
                                drawRoundRect(
                                    edge,
                                    Offset(topLeft.x + border / 2, topLeft.y + border / 2),
                                    Size(box.width - border, box.height - border),
                                    radius,
                                    style = Stroke(border),
                                )
                            }
                        }
                    }
                },
        )
        if (hint != null) {
            Text(hint, style = HexTheme.type.row, color = colors.text3, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * A long draft's chunk row (`.crow`): the chunk's number, one line of its text (four when selected), and its length,
 * in mono. Selected rows are raised; dim and past ones are quieter, and past ones (while recording) don't take taps.
 */
@Composable
fun HexChunkRow(
    number: Int,
    text: String,
    duration: String,
    state: HexChunkRowState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val type = HexTheme.type
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(HexTheme.radius.s)
    val selected = state == HexChunkRowState.Selected
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    when {
                        selected -> colors.tint(10f)
                        pressed -> colors.hover
                        else -> Color.Transparent
                    },
                ).then(if (selected) Modifier.border(1.dp, colors.tint(20f), shape) else Modifier)
                .then(if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) { this.selected = selected }
                .padding(6.dp),
    ) {
        Text(
            "$number",
            style = type.mono,
            color = colors.text3,
            textAlign = TextAlign.End,
            modifier = Modifier.width(16.dp).padding(top = 3.dp),
        )
        Text(
            text,
            style = type.row,
            color = if (state == HexChunkRowState.Dim || state == HexChunkRowState.Past) colors.text3 else colors.text1,
            maxLines = if (selected) 4 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(duration, style = type.mono, color = colors.text3, modifier = Modifier.padding(top = 3.dp))
    }
}
