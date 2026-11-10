package dev.opencode.mobile.ui.sessionchat.view

import android.content.ClipData
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.app.NewFeature
import dev.opencode.mobile.domain.app.NewFeatures
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal data class SessionChatMessageActionsTarget(
    val id: String,
    val text: String,
    val showsRevertMessage: Boolean,
    /** The code block the tap landed on, offered as its own copy action. */
    val codeBlock: String? = null,
)

@Composable
internal fun SessionChatMessageActionsSheet(
    target: SessionChatMessageActionsTarget?,
    onDismiss: () -> Unit,
    onRevertMessage: (String) -> Unit,
    onForkFromMessage: (messageId: String, messageText: String) -> Unit,
    onSelectText: (SessionChatMessageActionsTarget) -> Unit,
) {
    val context = LocalContext.current
    val messageLabel = stringResource(R.string.session_chat_sheet_message_actions_title)
    val codeLabel = stringResource(R.string.session_chat_sheet_message_actions_clip_label_code)
    // Read here, not in the sheet body, so the badge stays put while the sheet closes.
    val selectTextIsNew = NewFeatures.isNew(NewFeature.SelectMessageText)

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_MESSAGE_ACTIONS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    OpenCodeBottomSheet(
        visible = target != null,
        title = messageLabel,
        onDismissRequest = {
            logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_DISMISS)
            onDismiss()
        },
    ) {
        if (target?.showsRevertMessage == true) {
            OpenCodeBottomSheetEntry(
                title = stringResource(R.string.session_chat_sheet_message_actions_revert),
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Undo),
                onClick = {
                    logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_REVERT)
                    onRevertMessage(target.id)
                    onDismiss()
                },
            )
            OpenCodeBottomSheetEntry(
                title = stringResource(R.string.session_chat_sheet_message_actions_fork),
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.CallSplit),
                onClick = {
                    logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_FORK)
                    onForkFromMessage(target.id, target.text)
                    onDismiss()
                },
            )
        }
        target?.codeBlock?.let { code ->
            OpenCodeBottomSheetEntry(
                title = stringResource(R.string.session_chat_sheet_message_actions_copy_code_block),
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Code),
                onClick = {
                    logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_COPY_CODE_BLOCK)
                    context.copyToClipboard(label = codeLabel, text = code)
                    onDismiss()
                },
            )
        }
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.session_chat_sheet_message_actions_copy_message),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy),
            onClick = {
                logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_COPY)
                target?.text?.let { text -> context.copyToClipboard(label = messageLabel, text = text) }
                onDismiss()
            },
        )
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.session_chat_sheet_message_actions_select_text),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.TextSelect),
            isNew = selectTextIsNew,
            onClick = {
                logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_SELECT_TEXT)
                NewFeatures.markUsed(NewFeature.SelectMessageText)
                target?.let(onSelectText)
                onDismiss()
            },
        )
    }
}

internal fun Context.copyToClipboard(
    label: String,
    text: String,
) {
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun logMessageActionsAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_MESSAGE_ACTIONS,
        actionId = actionId,
    )
}
