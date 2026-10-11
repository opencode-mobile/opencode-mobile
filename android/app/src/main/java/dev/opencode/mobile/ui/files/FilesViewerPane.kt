package dev.opencode.mobile.ui.files

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductRouteScreenViewEffect
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeCodeFile
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File Viewer: header with Copy path / Wrap lines, then the content state for [viewer]. */
@Composable
internal fun FilesViewerPane(
    viewer: FilesViewerState,
    wrapLines: Boolean,
    leading: OpenCodeTopBarLeading,
    onCopyPath: () -> Unit,
    onToggleWrap: () -> Unit,
    onRetry: () -> Unit,
    onToggleMarkdownSource: () -> Unit,
    tabs: (@Composable () -> Unit)? = null,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    ProductRouteScreenViewEffect(screenId = ProductAnalytics.Screen.FILES_VIEWER)
    Column(Modifier.fillMaxSize()) {
        tabs?.invoke()
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = "Copy path",
                        icon = OpenCodeIcons.ContentCopy,
                        onClick = {
                            menuExpanded = false
                            onCopyPath()
                        },
                    ),
                    OpenCodeOverflowMenuEntry(
                        title = "Wrap lines",
                        icon = if (wrapLines) OpenCodeIcons.Check else null,
                        accessibilityLabel = if (wrapLines) "Wrap lines, on" else "Wrap lines, off",
                        onClick = {
                            menuExpanded = false
                            onToggleWrap()
                        },
                    ),
                ),
        ) {
            OpenCodeTopBar(
                title = viewer.name,
                subtitle = viewer.path,
                leading = leading,
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "File options",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
        Box(Modifier.weight(1f)) {
            FilesViewerBody(viewer, wrapLines, onRetry, onToggleMarkdownSource)
        }
    }
}

@Composable
private fun FilesViewerBody(
    viewer: FilesViewerState,
    wrapLines: Boolean,
    onRetry: () -> Unit,
    onToggleMarkdownSource: () -> Unit,
) {
    when (val content = viewer.content) {
        FilesViewerContent.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OpenCodeStatusStack(title = "Opening file", style = OpenCodeStatusStackStyle.Spinner)
            }
        is FilesViewerContent.Text -> TextContent(viewer, content, wrapLines, onToggleMarkdownSource)
        is FilesViewerContent.Image -> ImageContent(content)
        is FilesViewerContent.Binary -> BinaryPlaceholder(content.mimeType)
        FilesViewerContent.Empty ->
            Centered {
                OpenCodeEmptyState(
                    title = "Empty file",
                    message = "This file has no content.",
                    icon = OpenCodeIcons.Description,
                )
            }
        is FilesViewerContent.Failed ->
            Centered {
                OpenCodeEmptyState(
                    title = "Couldn't open file",
                    message = content.message,
                    primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = onRetry),
                )
            }
    }
}

@Composable
private fun TextContent(
    viewer: FilesViewerState,
    content: FilesViewerContent.Text,
    wrapLines: Boolean,
    onToggleMarkdownSource: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (content.isTruncated) {
            OpenCodeInlineNotice(
                message = "Showing the first 1 MB of this file.",
                variant = OpenCodeInlineNoticeVariant.Banner,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            val markdown = content.markdown
            if (markdown != null && viewer.showsMarkdownPreview) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    OpenCodeRenderedMarkdown(document = markdown, variant = OpenCodeRenderedMarkdownVariant.Standalone)
                }
            } else {
                OpenCodeCodeFile(lines = content.lines, wrapLines = wrapLines, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(openCodeColor("border-weaker-base")),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = content.metaLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = openCodeColor("text-weak"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (content.markdown != null) {
                OpenCodeChoiceBadge(
                    label = "Preview",
                    selected = !viewer.showsMarkdownSource,
                    onClick = { if (viewer.showsMarkdownSource) onToggleMarkdownSource() },
                )
                OpenCodeChoiceBadge(
                    label = "Source",
                    selected = viewer.showsMarkdownSource,
                    onClick = { if (!viewer.showsMarkdownSource) onToggleMarkdownSource() },
                )
            }
        }
    }
}

private sealed interface DecodedImage {
    data object Decoding : DecodedImage

    data class Ready(
        val bitmap: ImageBitmap,
    ) : DecodedImage

    data object Failed : DecodedImage
}

@Composable
private fun ImageContent(content: FilesViewerContent.Image) {
    val decoded by produceState<DecodedImage>(DecodedImage.Decoding, content) {
        value =
            withContext(Dispatchers.Default) {
                decodeSampledBitmap(content.bytes)?.let { DecodedImage.Ready(it) } ?: DecodedImage.Failed
            }
    }
    when (val image = decoded) {
        DecodedImage.Decoding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OpenCodeStatusStack(title = "Opening image", style = OpenCodeStatusStackStyle.Spinner)
            }
        is DecodedImage.Ready ->
            Image(
                bitmap = image.bitmap,
                contentDescription = "Image preview",
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
            )
        DecodedImage.Failed -> BinaryPlaceholder(content.mimeType)
    }
}

/** Decodes with a power-of-two sample size so huge dimensions cannot exhaust memory. */
private fun decodeSampledBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()

private const val MAX_IMAGE_DIMENSION = 4096

@Composable
private fun BinaryPlaceholder(mimeType: String?) {
    Centered {
        OpenCodeEmptyState(
            title = "Binary file",
            message = listOfNotNull(mimeType?.takeIf { it.isNotBlank() }, "No preview for this file type.").joinToString("\n"),
            icon = OpenCodeIcons.Description,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Open files on split layouts: tap to switch, × to close. */
@Composable
internal fun FilesTabStrip(
    tabs: List<String>,
    activePath: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { path ->
                FilesTab(
                    name = path.substringAfterLast('/'),
                    active = path == activePath,
                    onSelect = { onSelect(path) },
                    onClose = { onClose(path) },
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(openCodeColor("border-weaker-base")),
        )
    }
}

@Composable
private fun FilesTab(
    name: String,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(OpenCodeRadius.small)
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(if (active) openCodeColor("surface-raised-base") else Color.Transparent, shape)
                .border(1.dp, if (active) openCodeColor("border-weak-base") else Color.Transparent, shape)
                .clickable(onClick = onSelect)
                .semantics {
                    role = Role.Tab
                    selected = active
                    contentDescription = name
                }.padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.widthIn(max = 180.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) openCodeColor("text-strong") else openCodeColor("text-weak"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(shape)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "Close $name" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Close,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = openCodeColor("icon-base"),
            )
        }
    }
}
