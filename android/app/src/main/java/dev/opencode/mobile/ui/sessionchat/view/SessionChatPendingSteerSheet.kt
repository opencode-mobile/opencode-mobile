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

internal data class SessionChatPendingSteerTarget(
    val id: String,
    val text: String,
)

@Composable
internal fun SessionChatPendingSteerSheet(
    target: SessionChatPendingSteerTarget?,
    onDismiss: () -> Unit,
    onMoveToQueue: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_PENDING_STEER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = target != null,
    )

    OpenCodeBottomSheet(
        visible = target != null,
        title = "Pending steer",
        onDismissRequest = {
            logPendingSteerAction(ProductAnalytics.Action.PENDING_STEER_DISMISS)
            onDismiss()
        },
    ) {
        OpenCodeBottomSheetEntry(
            title = "Move to queue",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ArrowUpward, "Move to queue"),
            onClick = {
                logPendingSteerAction(ProductAnalytics.Action.PENDING_STEER_QUEUE)
                target?.id?.let(onMoveToQueue)
                onDismiss()
            },
        )
        OpenCodeBottomSheetEntry(
            title = "Delete",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Delete, "Delete"),
            onClick = {
                logPendingSteerAction(ProductAnalytics.Action.PENDING_STEER_DELETE)
                target?.id?.let(onDelete)
                onDismiss()
            },
        )
        OpenCodeBottomSheetEntry(
            title = "Copy text",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy, "Copy text"),
            onClick = {
                logPendingSteerAction(ProductAnalytics.Action.PENDING_STEER_COPY)
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

private fun logPendingSteerAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SESSION_PENDING_STEER,
        actionId = actionId,
    )
}
