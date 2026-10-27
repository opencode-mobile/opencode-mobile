package dev.opencode.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Tallest an inline timeline image gets, so a phone screenshot does not take over the thread. */
val OpenCodeMessageImageMaxHeight: Dp = 220.dp

private const val INLINE_DECODE_MAX_DIMENSION = 1280
private const val VIEWER_DECODE_MAX_DIMENSION = 2560

/**
 * Inline timeline image: whole picture at its own aspect ratio, never cropped, at most
 * [OpenCodeMessageImageMaxHeight] tall. Tap opens [OpenCodeImageViewer]. While the bytes load
 * (a server preview), and when they cannot be loaded, [fallback] draws the attachment chip.
 */
@Composable
fun OpenCodeMessageImage(
    attachment: OpenCodeAttachmentPresentation,
    modifier: Modifier = Modifier,
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
                    .clickable(onClickLabel = "View full screen", role = Role.Button) { viewerOpen = true },
        )
    }
    if (viewerOpen) {
        OpenCodeImageViewer(attachment = attachment, onDismiss = { viewerOpen = false })
    }
}

/**
 * Full-screen viewer: the whole image letterboxed on the theme's scrim (black). A tap outside the
 * image, or the close control, dismisses; a tap on the image itself does not. The close icon stays
 * white because no token reads on a black letterbox in both themes.
 */
@Composable
fun OpenCodeImageViewer(
    attachment: OpenCodeAttachmentPresentation,
    onDismiss: () -> Unit,
) {
    val bitmap = rememberAttachmentImage(attachment, VIEWER_DECODE_MAX_DIMENSION)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close image",
                        onClick = onDismiss,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val boxRatio = maxWidth.value / maxHeight.value
                val fitted =
                    if (ratio >= boxRatio) {
                        Pair(maxWidth, (maxWidth.value / ratio).dp)
                    } else {
                        Pair((maxHeight.value * ratio).dp, maxHeight)
                    }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = attachment.filename,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .size(width = fitted.first, height = fitted.second)
                            // Swallow taps so only the letterbox around the image dismisses.
                            .pointerInput(Unit) { detectTapGestures { } },
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(8.dp)
                        .size(48.dp),
            ) {
                Icon(OpenCodeIcons.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

/**
 * The attachment's image, best source first: the server file or full data URL decoded to
 * [maxDimension], the thumbnail while that loads. Null while nothing is decodable or when every
 * source failed.
 */
@Composable
private fun rememberAttachmentImage(
    attachment: OpenCodeAttachmentPresentation,
    maxDimension: Int,
): Bitmap? {
    val loader = LocalOpenCodeAttachmentImageLoader.current
    val state =
        produceState<Bitmap?>(
            initialValue = null,
            attachment.id,
            attachment.imageBytes,
            attachment.imageSource,
            maxDimension,
        ) {
            val thumbnail = attachment.imageBytes?.let { withContext(Dispatchers.Default) { decodeScaled(it, maxDimension) } }
            value = thumbnail
            val full =
                when (val source = attachment.imageSource) {
                    is OpenCodeAttachmentImageSource.ServerPath -> loader(source)
                    is OpenCodeAttachmentImageSource.DataUrl ->
                        withContext(Dispatchers.Default) { AttachmentDataUrl.decode(source.dataUrl, source.mime) }
                    null -> null
                }
            if (full != null) {
                withContext(Dispatchers.Default) { decodeScaled(full, maxDimension) }?.let { value = it }
            }
        }
    return state.value
}

private fun decodeScaled(
    bytes: ByteArray,
    maxDimension: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDimension || bounds.outHeight / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
