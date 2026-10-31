package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.opencode.mobile.ui.components.OpenCodeImageGalleryViewer
import dev.opencode.mobile.ui.sessionchat.SessionChatAttachmentItem
import dev.opencode.mobile.ui.sessionchat.SessionChatGalleryImageLoader
import dev.opencode.mobile.ui.sessionchat.SessionChatImageGalleries
import dev.opencode.mobile.ui.sessionchat.SessionChatImageViewerState
import kotlinx.coroutines.launch

/**
 * The full-screen viewer for [state] over [images] (the open gallery), with Share writing the
 * current page through [SessionChatImageShare]. Draws nothing while closed or the gallery is gone.
 */
@Composable
internal fun SessionChatImageViewerHost(
    state: SessionChatImageViewerState?,
    images: List<SessionChatAttachmentItem>?,
    loader: SessionChatGalleryImageLoader,
    onPageChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == null || images.isNullOrEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OpenCodeImageGalleryViewer(
        images = images.map(SessionChatImageGalleries::galleryImage),
        index = state.index,
        onIndexChange = onPageChange,
        onDismiss = onDismiss,
        onShare = { page ->
            val item = images.getOrNull(page) ?: return@OpenCodeImageGalleryViewer
            scope.launch {
                val bytes = loader.load(item.id) ?: return@launch
                SessionChatImageShare.chooser(context, bytes, item.filename)?.let { chooser ->
                    runCatching { context.startActivity(chooser) }
                }
            }
        },
    )
}
