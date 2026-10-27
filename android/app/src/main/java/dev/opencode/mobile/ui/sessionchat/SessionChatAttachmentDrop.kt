package dev.opencode.mobile.ui.sessionchat

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.DragAndDropPermissions
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import dev.opencode.mobile.domain.message.AttachmentDropContext
import dev.opencode.mobile.domain.message.AttachmentDropItem
import dev.opencode.mobile.domain.message.AttachmentDropKind
import dev.opencode.mobile.domain.message.AttachmentDropRouting
import dev.opencode.mobile.domain.message.AttachmentDropSummary
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant

/** Files dropped on Session Chat: content URIs readable until [permissions] are released. */
internal class SessionChatDroppedFiles(
    val uris: List<Uri>,
    private val permissions: DragAndDropPermissions?,
) {
    fun release() {
        runCatching { permissions?.release() }
    }
}

/**
 * Makes Session Chat a drop target for images and files (#870). Android names only the types in a
 * drag until the drop, so the card counts kinds, not items. A drag that carries only text shows
 * no card; dropped anywhere but the prompt field, it does nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.sessionChatAttachmentDropTarget(
    enabled: Boolean,
    dropContext: () -> AttachmentDropContext,
    onSummaryChange: (AttachmentDropSummary?) -> Unit,
    onDrop: (SessionChatDroppedFiles) -> Unit,
): Modifier {
    val activity = LocalContext.current.findActivity()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentContext by rememberUpdatedState(dropContext)
    val currentOnSummaryChange by rememberUpdatedState(onSummaryChange)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val target =
        remember {
            object : DragAndDropTarget {
                private fun summary(event: DragAndDropEvent): AttachmentDropSummary? =
                    AttachmentDropRouting.summary(
                        items = sessionChatDropItems(event.mimeTypes()),
                        context = currentContext(),
                        countKnown = false,
                    )

                override fun onEntered(event: DragAndDropEvent) {
                    currentOnSummaryChange(summary(event))
                }

                override fun onExited(event: DragAndDropEvent) {
                    currentOnSummaryChange(null)
                }

                override fun onEnded(event: DragAndDropEvent) {
                    currentOnSummaryChange(null)
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val summary = summary(event)
                    currentOnSummaryChange(null)
                    // The card already said nothing can be attached; the drop fails like a no-drop.
                    if (summary?.rejected == true) return false
                    val dragEvent = event.toAndroidDragEvent()
                    val clip = dragEvent.clipData ?: return false
                    // Only now are the items known. Text carries no URI; a text file does, even
                    // though its `text/plain` type showed no card while it was dragged.
                    val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    if (uris.isEmpty()) return false
                    val permissions = runCatching { activity?.requestDragAndDropPermissions(dragEvent) }.getOrNull()
                    currentOnDrop(SessionChatDroppedFiles(uris, permissions))
                    return true
                }
            }
        }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event -> currentEnabled && event.mimeTypes().isNotEmpty() },
        target = target,
    )
}

/** One item per non-text type in the drag; text (a selection, a link) is never an attachment here. */
internal fun sessionChatDropItems(mimeTypes: Set<String>): List<AttachmentDropItem> =
    mimeTypes
        .filterNot { it.startsWith("text/") }
        .map { AttachmentDropItem(kind = AttachmentDropRouting.kind(it, null)) }

/** Whether a dropped URI names a folder, which cannot be attached. */
internal fun ContentResolver.isFolder(uri: Uri): Boolean =
    AttachmentDropRouting.kind(runCatching { getType(uri) }.getOrNull(), null) == AttachmentDropKind.Folder

internal fun AttachmentDropKind.toAttachmentVariant(): OpenCodeAttachmentVariant =
    when (this) {
        AttachmentDropKind.Image -> OpenCodeAttachmentVariant.Image
        AttachmentDropKind.Pdf -> OpenCodeAttachmentVariant.Pdf
        AttachmentDropKind.Text -> OpenCodeAttachmentVariant.Text
        AttachmentDropKind.File, AttachmentDropKind.Folder -> OpenCodeAttachmentVariant.File
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
