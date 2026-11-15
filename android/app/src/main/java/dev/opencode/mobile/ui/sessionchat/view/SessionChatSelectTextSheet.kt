package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.LocalOpenCodeBottomSheetBodyHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeSelectionContainer
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.components.openCodeBottomSheetScrollTarget
import kotlin.math.roundToInt

/** Tallest stretch of a code block the sheet centers; a longer block shows from its start. */
private val SelectTextScrollTargetMaxHeight = 160.dp

/**
 * Text larger than this (UTF-16 units) opens in a half-height sheet whose body lays out only the
 * lines in view, instead of one `Text` as tall as the whole text: a pasted log of 190,000 lines
 * must not lay out or measure the whole text at once (#1043).
 */
internal const val SELECT_TEXT_SELF_SCROLLING_THRESHOLD = 32 * 1_024

/** Source lines per lazily laid-out piece of a very long text. */
internal const val SELECT_TEXT_CHUNK_LINES = 40

/** Longest piece of one source line laid out at once. */
internal const val SELECT_TEXT_CHUNK_CHARACTERS = 4_000

/** Line height kept at both ends of a piece, so no seam shows where two pieces meet. */
private val SelectTextChunkLineHeightStyle =
    LineHeightStyle(alignment = LineHeightStyle.Alignment.Proportional, trim = LineHeightStyle.Trim.None)

/** Vertical padding around the body text, top and bottom. */
private val SelectTextBodyVerticalPadding = 8.dp

/** Until the sheet reports its body, a height no half sheet body exceeds. */
private val SelectTextProvisionalBodyHeight = 320.dp

internal fun selectTextScrollsItself(text: String): Boolean = text.length > SELECT_TEXT_SELF_SCROLLING_THRESHOLD

/**
 * A message's raw text as one selectable block, so any part of it can be selected and copied.
 * It shows exactly what Copy message copies. Opened from a code block, it starts scrolled to
 * that block. Copy all copies the whole text and closes the sheet.
 */
@Composable
internal fun SessionChatSelectTextSheet(
    target: SessionChatMessageActionsTarget?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.session_chat_sheet_message_actions_title)

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_SELECT_TEXT,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    val codeBlockRange = remember(target) { target?.let { selectTextCodeBlockRange(it.text, it.codeBlock) } }
    val scrollsItself = remember(target) { target?.let { selectTextScrollsItself(it.text) } ?: false }
    OpenCodeBottomSheet(
        visible = target != null,
        title =
            stringResource(
                if (target?.isFullMessage == true) {
                    R.string.session_chat_sheet_select_text_full_message_title
                } else {
                    R.string.session_chat_sheet_select_text_title
                },
            ),
        onDismissRequest = {
            logSelectTextAction(ProductAnalytics.Action.SELECT_TEXT_DISMISS)
            onDismiss()
        },
        height = if (scrollsItself) OpenCodeBottomSheetHeight.Half else OpenCodeBottomSheetHeight.FitAllContent,
        scrollTargetKey = target?.takeIf { codeBlockRange != null && !scrollsItself },
        pinnedBottom = {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.session_chat_sheet_select_text_copy_all)),
                onClick = {
                    logSelectTextAction(ProductAnalytics.Action.SELECT_TEXT_COPY_ALL)
                    target?.text?.let { text -> context.copyToClipboard(label = clipLabel, text = text) }
                    onDismiss()
                },
            )
        },
    ) {
        when {
            target == null -> Unit
            scrollsItself -> SessionChatScrollingSelectTextBody(target = target, codeBlockRange = codeBlockRange)
            else -> SessionChatSelectTextBody(target = target, codeBlockRange = codeBlockRange)
        }
    }
}

/**
 * A very long text in a list that fills the sheet body and lays out only the pieces in view.
 * Same style and selection container as the short body; a drag selection reaches as far as the
 * pieces on screen, and Copy all copies the whole text.
 */
@Composable
private fun SessionChatScrollingSelectTextBody(
    target: SessionChatMessageActionsTarget,
    codeBlockRange: IntRange?,
) {
    val chunks = remember(target.text) { selectTextChunks(target.text) }
    val listState = rememberLazyListState()
    val bodyHeight = LocalOpenCodeBottomSheetBodyHeight.current ?: SelectTextProvisionalBodyHeight
    LaunchedEffect(target, codeBlockRange) {
        val start = codeBlockRange?.first ?: return@LaunchedEffect
        val index = chunks.indexOfFirst { start in it }
        if (index >= 0) listState.scrollToItem(index)
    }
    OpenCodeSelectionContainer {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height((bodyHeight - SelectTextBodyVerticalPadding * 2).coerceAtLeast(1.dp))
                    .openCodeBottomSheetContentInset(),
            contentPadding = PaddingValues(vertical = SelectTextBodyVerticalPadding),
        ) {
            items(count = chunks.size, key = { index -> chunks[index].first }) { index ->
                val chunk = chunks[index]
                Text(
                    text = target.text.substring(chunk.first, chunk.last + 1),
                    modifier = Modifier.fillMaxWidth(),
                    // Keep each piece's outer half-leading so the pieces stack at the body's line spacing.
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeightStyle = SelectTextChunkLineHeightStyle),
                    color = openCodeColor("text-strong"),
                )
            }
        }
    }
}

/**
 * Splits [text] into pieces of at most [SELECT_TEXT_CHUNK_LINES] source lines, a long line into
 * pieces of at most [SELECT_TEXT_CHUNK_CHARACTERS] characters (never between a surrogate pair).
 * Each range leaves out the line break that ends it, so the pieces stack as the text's lines.
 */
internal fun selectTextChunks(text: String): List<IntRange> {
    val chunks = ArrayList<IntRange>()
    var start = 0
    var lines = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\n') {
            lines += 1
            if (lines == SELECT_TEXT_CHUNK_LINES) {
                chunks += start until index
                start = index + 1
                lines = 0
            }
        } else if (index - start + 1 >= SELECT_TEXT_CHUNK_CHARACTERS && !Character.isHighSurrogate(character)) {
            chunks += start..index
            start = index + 1
            lines = 0
        }
        index += 1
    }
    if (start < text.length || chunks.isEmpty()) chunks += start until text.length
    return chunks
}

@Composable
private fun SessionChatSelectTextBody(
    target: SessionChatMessageActionsTarget,
    codeBlockRange: IntRange?,
) {
    var layout by remember(target) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    OpenCodeSelectionContainer {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .openCodeBottomSheetContentInset()
                    .padding(vertical = SelectTextBodyVerticalPadding),
        ) {
            Text(
                text = target.text,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
                onTextLayout = { layout = it },
            )
            // An empty marker over the code block's first lines; the sheet scrolls to it.
            val block = layout?.let { selectTextLineSpan(it, codeBlockRange) }
            if (block != null) {
                val maxHeightPx = with(density) { SelectTextScrollTargetMaxHeight.toPx() }
                val heightPx = (block.endInclusive - block.start).coerceAtMost(maxHeightPx)
                Box(
                    modifier =
                        Modifier
                            .offset { IntOffset(x = 0, y = block.start.roundToInt()) }
                            .fillMaxWidth()
                            .height(with(density) { heightPx.toDp() })
                            .openCodeBottomSheetScrollTarget(target),
                )
            }
        }
    }
}

/** Top and bottom, in the text's own coordinates, of the lines that hold [range]. */
private fun selectTextLineSpan(
    layout: TextLayoutResult,
    range: IntRange?,
): ClosedFloatingPointRange<Float>? {
    val length = layout.layoutInput.text.length
    if (range == null || range.first !in 0 until length) return null
    val firstLine = layout.getLineForOffset(range.first)
    val lastLine = layout.getLineForOffset(range.last.coerceIn(range.first, length - 1))
    return layout.getLineTop(firstLine)..layout.getLineBottom(lastLine)
}

/**
 * Where [codeBlock] sits in the message's raw [text], or null when it has none or cannot be
 * found. The rendered block can differ from the source by surrounding whitespace, so a miss
 * falls back to the trimmed block and then to its first non-blank line. A block that appears
 * more than once resolves to its first occurrence.
 */
internal fun selectTextCodeBlockRange(
    text: String,
    codeBlock: String?,
): IntRange? {
    if (codeBlock.isNullOrBlank()) return null
    val candidates =
        listOfNotNull(
            codeBlock,
            codeBlock.trim(),
            codeBlock.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
        )
    candidates.forEach { candidate ->
        val start = text.indexOf(candidate)
        if (start >= 0) return start until start + candidate.length
    }
    return null
}

private fun logSelectTextAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_SELECT_TEXT,
        actionId = actionId,
    )
}
