package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.message.FailureAction
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeSemanticFallbackHighlighter
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the failure sheet can do besides showing a provider body. */
internal enum class SessionFailureSheetAction {
    PickModel,
    Compact,
    CopyCaption,
}

internal data class SessionChatToolDetailTarget(
    val title: String,
    val body: String,
    /** Render [body] as markdown (a compaction summary) instead of plain tool output. */
    val bodyIsMarkdown: Boolean = false,
    /** Typed inspector body. Failure sheets and compaction summaries leave this null. */
    val presentation: dev.opencode.mobile.ui.sessionchat.ToolResultPresentation? = null,
    /** Provider error bodies are monospace and copyable. */
    val monospace: Boolean = false,
    val sheetAction: SessionFailureSheetAction? = null,
    val sheetActionLabel: String? = null,
    /** Live retry link, shown when this sheet was opened from the composer status. */
    val sheetLinkLabel: String? = null,
    val sheetLink: String? = null,
)

/**
 * Sheet for a failure mark. Copy of a provider body stays on the monospace body.
 * Copy with no body, Compact, and Pick a model are the sheet action.
 */
internal fun sessionFailureDetailTarget(
    text: String,
    providerBody: String?,
    action: FailureAction?,
    actionLabel: String?,
    linkLabel: String? = null,
    link: String? = null,
): SessionChatToolDetailTarget? {
    val body = providerBody?.trim()?.takeIf { it.isNotEmpty() }
    val label = actionLabel?.trim()?.takeIf { it.isNotEmpty() }
    val sheetAction =
        when {
            action == null || label == null -> null
            action == FailureAction.Copy && body != null -> null
            action == FailureAction.Copy -> SessionFailureSheetAction.CopyCaption
            action == FailureAction.PickModel -> SessionFailureSheetAction.PickModel
            action == FailureAction.Compact -> SessionFailureSheetAction.Compact
            else -> null
        }
    val resolvedLink = link?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedLinkLabel = linkLabel?.trim()?.takeIf { it.isNotEmpty() && resolvedLink != null }
    if (body == null && sheetAction == null && resolvedLinkLabel == null) return null
    return SessionChatToolDetailTarget(
        title = if (body != null) "Provider response" else text,
        body = body.orEmpty(),
        monospace = body != null,
        sheetAction = sheetAction,
        sheetActionLabel = if (sheetAction != null) label else null,
        sheetLinkLabel = resolvedLinkLabel,
        sheetLink = if (resolvedLinkLabel != null) resolvedLink else null,
    )
}

@Composable
internal fun SessionChatToolDetailSheet(
    target: SessionChatToolDetailTarget?,
    onDismiss: () -> Unit,
    onFailureAction: (FailureAction) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_TOOL_DETAIL,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    val clipboard = LocalClipboardManager.current
    val action = target?.sheetAction
    val actionLabel = target?.sheetActionLabel?.takeIf { it.isNotBlank() }
    val linkLabel = target?.sheetLinkLabel?.takeIf { it.isNotBlank() }
    val link = target?.sheetLink?.takeIf { it.isNotBlank() }
    val hasAction = action != null && actionLabel != null
    val hasLink = linkLabel != null && link != null
    val presentation = target?.presentation?.takeIf { target.bodyIsMarkdown.not() }
    val copies = presentation?.copiesOriginal == true && !hasAction && !hasLink

    OpenCodeBottomSheet(
        visible = target != null,
        title = presentation?.title ?: target?.title ?: "Tool",
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SESSION_TOOL_DETAIL,
                actionId = ProductAnalytics.Action.TOOL_DETAIL_DISMISS,
            )
            onDismiss()
        },
        pinnedTop = {
            if (presentation != null) {
                ToolResultPinnedHeader(presentation)
            }
        },
        pinnedBottom =
            if (copies) {
                {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Copy output"),
                        onClick = { clipboard.setText(AnnotatedString(presentation.raw)) },
                    )
                }
            } else if (hasAction || hasLink) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (action != null && actionLabel != null) {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.FullWidthText(actionLabel),
                                onClick = {
                                    when (action) {
                                        SessionFailureSheetAction.CopyCaption ->
                                            clipboard.setText(AnnotatedString(target?.title.orEmpty()))
                                        SessionFailureSheetAction.PickModel -> {
                                            onDismiss()
                                            onFailureAction(FailureAction.PickModel)
                                        }
                                        SessionFailureSheetAction.Compact -> {
                                            onDismiss()
                                            onFailureAction(FailureAction.Compact)
                                        }
                                    }
                                },
                            )
                        }
                        if (linkLabel != null && link != null) {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.FullWidthText(linkLabel),
                                onClick = { onOpenLink(link) },
                            )
                        }
                    }
                }
            } else {
                null
            },
    ) {
        when {
            presentation != null ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .openCodeBottomSheetContentInset()
                            .padding(bottom = 12.dp),
                ) {
                    ToolResultBody(presentation)
                }
            target != null -> SessionChatToolDetailBody(target)
        }
    }
}

/**
 * The detail body: [SessionChatToolDetailTarget.body] as markdown, a
 * monospace provider body, or plain text. Shared by the tool detail sheet
 * and the activity sheet's in-place step detail (#779).
 */
@Composable
internal fun SessionChatToolDetailBody(target: SessionChatToolDetailTarget) {
    val presentation = target.presentation?.takeUnless { target.bodyIsMarkdown }
    if (presentation != null) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .openCodeBottomSheetContentInset()
                    .padding(vertical = 12.dp),
        ) {
            ToolResultPinnedHeader(presentation)
            ToolResultBody(presentation)
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    val body = target.body
    val document by produceState<MarkdownDocument?>(initialValue = null, body, target.bodyIsMarkdown) {
        value =
            if (target.bodyIsMarkdown && body.isNotBlank()) {
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
    if (rendered == null && body.isBlank()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .openCodeBottomSheetContentInset()
                .padding(vertical = 12.dp),
    ) {
        when {
            rendered != null ->
                OpenCodeRenderedMarkdown(
                    document = rendered,
                    variant = OpenCodeRenderedMarkdownVariant.Standalone,
                )
            target.monospace && body.isNotBlank() ->
                SessionProviderResponseBody(
                    body = body,
                    onCopy = { clipboard.setText(AnnotatedString(body)) },
                )
            body.isNotBlank() ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
        }
    }
}
