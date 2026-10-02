package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SessionChatToolDetailTarget(
    val title: String,
    val body: String,
    /** Render [body] as markdown (a compaction summary) instead of plain tool output. */
    val bodyIsMarkdown: Boolean = false,
)

@Composable
internal fun SessionChatToolDetailSheet(
    target: SessionChatToolDetailTarget?,
    onDismiss: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_TOOL_DETAIL,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    OpenCodeBottomSheet(
        visible = target != null,
        title = target?.title.orEmpty(),
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_TOOL_DETAIL,
                actionId = ProductAnalytics.Action.TOOL_DETAIL_DISMISS,
            )
            onDismiss()
        },
        contentMode = OpenCodeBottomSheetContentMode.ActionList,
    ) {
        val body = target?.body.orEmpty()
        val bodyModifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        val document by produceState<MarkdownDocument?>(initialValue = null, body, target?.bodyIsMarkdown) {
            value =
                if (target?.bodyIsMarkdown == true && body.isNotBlank()) {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            OpenCodeMarkdownParser.parse(body, codeHighlighter = OpenCodeSemanticFallbackHighlighter)
                        }.getOrNull()
                    }
                } else {
                    null
                }
        }
        val rendered = document
        if (rendered != null) {
            OpenCodeRenderedMarkdown(
                document = rendered,
                variant = OpenCodeRenderedMarkdownVariant.Standalone,
                modifier = bodyModifier,
            )
        } else {
            Text(
                text = body,
                modifier = bodyModifier,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
