package dev.opencode.mobile.ui.sessionchat.view

import android.content.ClipData
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal data class SessionChatMessageActionsTarget(
    val text: String,
    val showsRevertMessage: Boolean,
)

@Composable
internal fun SessionChatMessageActionsSheet(
    target: SessionChatMessageActionsTarget?,
    onDismiss: () -> Unit,
    onRestoreToComposer: (String) -> Unit,
) {
    val context = LocalContext.current

    OpenCodeBottomSheet(
        visible = target != null,
        title = "Message",
        onDismissRequest = onDismiss,
    ) {
        if (target?.showsRevertMessage == true) {
            OpenCodeBottomSheetEntry(
                title = "Revert message",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Undo, "Revert message"),
                onClick = {
                    target.let { onRestoreToComposer(it.text) }
                    onDismiss()
                },
            )
        }
        OpenCodeBottomSheetEntry(
            title = "Copy message",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy, "Copy message"),
            onClick = {
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
