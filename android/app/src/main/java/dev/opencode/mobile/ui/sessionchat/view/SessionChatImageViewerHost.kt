package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.opencode.mobile.ui.components.OpenCodeImageGalleryViewer
import dev.opencode.mobile.ui.sessionchat.SessionChatAttachmentItem
import dev.opencode.mobile.ui.sessionchat.SessionChatFullImageMemory
import dev.opencode.mobile.ui.sessionchat.SessionChatGalleryImageLoader
import dev.opencode.mobile.ui.sessionchat.SessionChatImageGalleries
import dev.opencode.mobile.ui.sessionchat.SessionChatImageViewerState
import dev.opencode.mobile.ui.sessionchat.region.SessionChatFullImageModel
import kotlinx.coroutines.launch

/**
 * The full-screen viewer for [state] over [images] (the open gallery), with Share writing the
 * current page through [SessionChatImageShare]. A [SessionChatFullImageModel] loads each page's
 * full picture as the viewer settles on it (from memory, or from the server when only a cached
 * thumbnail is here); Share waits for the full picture. Draws nothing while closed or the
 * gallery is gone.
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
    remember(context) { SessionChatFullImageMemory.register(context) }
    val model = remember(loader, state.galleryId) { SessionChatFullImageModel(scope) { id -> loader.loadFull(id) } }
    val fullImages by model.state.collectAsState()
    val currentId = images.getOrNull(state.index.coerceIn(0, images.lastIndex))?.id
    LaunchedEffect(model, currentId) { currentId?.let(model::show) }
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
        pageStatus = fullImages::status,
        onRetry = { page -> images.getOrNull(page)?.let { model.retry(it.id) } },
    )
}
