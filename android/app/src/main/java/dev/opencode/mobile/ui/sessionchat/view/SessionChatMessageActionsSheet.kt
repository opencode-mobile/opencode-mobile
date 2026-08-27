package dev.opencode.mobile.ui.sessionchat.view

import android.content.ClipData
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal data class SessionChatMessageActionsTarget(
    val id: String,
    val text: String,
    val showsRevertMessage: Boolean,
)

@Composable
internal fun SessionChatMessageActionsSheet(
    target: SessionChatMessageActionsTarget?,
    onDismiss: () -> Unit,
    onRevertMessage: (String) -> Unit,
) {
    val context = LocalContext.current

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_MESSAGE_ACTIONS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    OpenCodeBottomSheet(
        visible = target != null,
        title = "Message",
        onDismissRequest = {
            logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_DISMISS)
            onDismiss()
        },
    ) {
        if (target?.showsRevertMessage == true) {
            OpenCodeBottomSheetEntry(
                title = "Revert message",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Undo, "Revert message"),
                onClick = {
                    logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_REVERT)
                    onRevertMessage(target.id)
                    onDismiss()
                },
            )
        }
        OpenCodeBottomSheetEntry(
            title = "Copy message",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy, "Copy message"),
            onClick = {
                logMessageActionsAction(ProductAnalytics.Action.MESSAGE_ACTIONS_COPY)
                target?.text?.let { text ->
                    val clipboardManager =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Message", text))
                }
                onDismiss()
            },
        )
    }
}

private fun logMessageActionsAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_MESSAGE_ACTIONS,
        actionId = actionId,
    )
}
