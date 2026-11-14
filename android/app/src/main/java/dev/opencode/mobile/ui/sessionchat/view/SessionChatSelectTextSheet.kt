package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.components.openCodeBottomSheetScrollTarget
import kotlin.math.roundToInt

/** Tallest stretch of a code block the sheet centers; a longer block shows from its start. */
private val SelectTextScrollTargetMaxHeight = 160.dp

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
    OpenCodeBottomSheet(
        visible = target != null,
        title = stringResource(R.string.session_chat_sheet_select_text_title),
        onDismissRequest = {
            logSelectTextAction(ProductAnalytics.Action.SELECT_TEXT_DISMISS)
            onDismiss()
        },
        height = OpenCodeBottomSheetHeight.FitAllContent,
        scrollTargetKey = target?.takeIf { codeBlockRange != null },
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
        if (target != null) SessionChatSelectTextBody(target = target, codeBlockRange = codeBlockRange)
    }
}

@Composable
private fun SessionChatSelectTextBody(
    target: SessionChatMessageActionsTarget,
    codeBlockRange: IntRange?,
) {
    var layout by remember(target) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    SelectionContainer {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .openCodeBottomSheetContentInset()
                    .padding(vertical = 8.dp),
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
