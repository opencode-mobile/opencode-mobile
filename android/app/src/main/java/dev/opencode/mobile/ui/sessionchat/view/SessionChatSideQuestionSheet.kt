package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.sessionchat.region.SessionChatSideQuestionAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A `/btw` answer: the question, the rendered markdown answer, and Copy / Done. Closing the
 * sheet forgets the answer.
 */
@Composable
internal fun SessionChatSideQuestionSheet(
    answer: SessionChatSideQuestionAnswer?,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    OpenCodeBottomSheet(
        visible = answer != null,
        title = "/btw",
        onDismissRequest = onClose,
        pinnedBottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Copy"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = {
                        val text = answer?.answer ?: return@OpenCodeButton
                        clipboard.setText(AnnotatedString(text))
                        onCopy()
                    },
                    modifier = Modifier.weight(1f),
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Done"),
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        if (answer != null) SessionChatSideQuestionBody(answer)
    }
}

@Composable
private fun SessionChatSideQuestionBody(answer: SessionChatSideQuestionAnswer) {
    val document by produceState<MarkdownDocument?>(initialValue = null, answer.id) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    OpenCodeMarkdownParser.parse(answer.answer, codeHighlighter = OpenCodeSemanticFallbackHighlighter)
                }.getOrNull()
            }
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .openCodeBottomSheetContentInset()
                .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = answer.question,
            style = MaterialTheme.typography.titleMedium,
            color = openCodeColor("text-strong"),
            modifier = Modifier.semantics { heading() },
        )
        val rendered = document
        if (rendered != null) {
            OpenCodeRenderedMarkdown(document = rendered, variant = OpenCodeRenderedMarkdownVariant.Standalone)
        } else {
            Text(
                text = answer.answer,
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
            )
        }
        Text(
            text = "Not added to the conversation",
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-weak"),
        )
    }
}
