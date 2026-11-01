package dev.opencode.mobile.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.theme.OpenCodeSmallShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Inline timeline image: whole picture at its own aspect ratio, never cropped, at most
 * [OpenCodeMessageImageMaxHeight] tall. Tap calls [onOpen] when the owner shows the viewer;
 * otherwise it opens [OpenCodeImageViewer] itself. While the bytes load (a server preview), and
 * when they cannot be loaded, [fallback] draws the attachment chip.
 */
@Composable
fun OpenCodeMessageImage(
    attachment: OpenCodeAttachmentPresentation,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    val image = rememberAttachmentImage(attachment, INLINE_DECODE_MAX_DIMENSION)
    var viewerOpen by remember(attachment.id) { mutableStateOf(false) }
    val bitmap = image
    if (bitmap == null) {
        fallback()
        return
    }
    BoxWithConstraints(modifier = modifier) {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val height = min(OpenCodeMessageImageMaxHeight.value, maxWidth.value / ratio).dp
        val width = height * ratio
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Image ${attachment.filename}",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .size(width = width, height = height)
                    .clip(OpenCodeSmallShape)
                    .openCodeFocusRing(OpenCodeSmallShape)
                    .clickable(onClickLabel = "View full screen", role = Role.Button) {
                        if (onOpen != null) onOpen() else viewerOpen = true
                    },
        )
    }
    if (viewerOpen) {
        OpenCodeImageViewer(attachment = attachment, onDismiss = { viewerOpen = false })
    }
}

/**
 * Full-screen viewer for one attachment: [OpenCodeImageGalleryViewer] with a single page, loading
 * the attachment's own image. Timeline images open the group viewer through the screen model instead.
 */
@Composable
fun OpenCodeImageViewer(
    attachment: OpenCodeAttachmentPresentation,
    onDismiss: () -> Unit,
) {
    val serverLoader = LocalOpenCodeAttachmentImageLoader.current
    val loader =
        remember(attachment, serverLoader) {
            object : OpenCodeGalleryImageLoader {
                override fun preview(id: String): ByteArray? = attachment.imageBytes

                override suspend fun load(id: String): ByteArray? = loadAttachmentBytes(attachment, serverLoader)
            }
        }
    CompositionLocalProvider(LocalOpenCodeGalleryImageLoader provides loader) {
        OpenCodeImageGalleryViewer(
            images = listOf(OpenCodeGalleryImage(id = attachment.id, name = attachment.filename)),
            index = 0,
            onIndexChange = {},
            onDismiss = onDismiss,
        )
    }
}

/** Full bytes of an attachment image: the server file, or the data URL decoded off the main thread. */
internal suspend fun loadAttachmentBytes(
    attachment: OpenCodeAttachmentPresentation,
    serverLoader: suspend (OpenCodeAttachmentImageSource.ServerPath) -> ByteArray?,
): ByteArray? =
    when (val source = attachment.imageSource) {
        is OpenCodeAttachmentImageSource.ServerPath -> serverLoader(source)
        is OpenCodeAttachmentImageSource.DataUrl ->
            withContext(Dispatchers.Default) { AttachmentDataUrl.decode(source.dataUrl, source.mime) }
        null -> null
    }

/**
 * The attachment's image, best source first: the decoded cache, then the server file or full data
 * URL decoded to [maxDimension], the thumbnail while that loads. Null while nothing is decodable
 * or when every source failed.
 */
@Composable
private fun rememberAttachmentImage(
    attachment: OpenCodeAttachmentPresentation,
    maxDimension: Int,
): Bitmap? {
    val loader = LocalOpenCodeAttachmentImageLoader.current
    val cached = remember(attachment.id, maxDimension) { OpenCodeImageBitmapCache.get(attachment.id, maxDimension) }
    val state =
        produceState(
            initialValue = cached,
            attachment.id,
            attachment.imageBytes,
            attachment.imageSource,
            maxDimension,
        ) {
            if (cached != null) return@produceState
            val thumbnail = attachment.imageBytes?.let { withContext(Dispatchers.Default) { decodeScaledBitmap(it, maxDimension) } }
            value = thumbnail
            val full = loadAttachmentBytes(attachment, loader)
            if (full != null) {
                withContext(Dispatchers.Default) { decodeScaledBitmap(full, maxDimension) }?.let { decoded ->
                    OpenCodeImageBitmapCache.put(attachment.id, maxDimension, decoded)
                    value = decoded
                }
            }
        }
    return state.value
}
