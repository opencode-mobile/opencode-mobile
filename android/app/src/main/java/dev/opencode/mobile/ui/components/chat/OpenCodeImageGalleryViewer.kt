package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val DISMISS_DRAG_FRACTION = 0.18f

/**
 * Full-screen viewer over a group of images: pages horizontally, pinch and double-tap zoom, swipe
 * down or the close control to dismiss. [index] is the page the owner shows; the viewer reports
 * every settled page through [onIndexChange]. [onShare], when set, adds a Share action for the
 * current page. Chrome is white on the black scrim in both themes, because it sits on pictures.
 */
@Composable
fun OpenCodeImageGalleryViewer(
    images: List<OpenCodeGalleryImage>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onShare: ((index: Int) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val count = images.size
    val pagerState = rememberPagerState(initialPage = index.coerceIn(0, count - 1)) { count }
    val currentOnIndexChange by rememberUpdatedState(onIndexChange)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { currentOnIndexChange(it) }
    }
    LaunchedEffect(index, count) {
        val target = index.coerceIn(0, count - 1)
        if (target != pagerState.settledPage) pagerState.scrollToPage(target)
    }
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    var pageZoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The backdrop fades as the picture is dragged away.
                        val progress = (abs(dismissOffset.value) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        alpha = 1f - progress
                    }.background(MaterialTheme.colorScheme.scrim),
        ) {
            val dismissDistance = constraints.maxHeight * DISMISS_DRAG_FRACTION
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !pageZoomed,
                beyondViewportPageCount = 1,
                key = { page -> images[page].id },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = dismissOffset.value }
                        .pointerInput(pageZoomed) {
                            if (pageZoomed) return@pointerInput
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dismissOffset.value > dismissDistance) {
                                        onDismiss()
                                    } else {
                                        scope.launch { dismissOffset.animateTo(0f) }
                                    }
                                },
                                onDragCancel = { scope.launch { dismissOffset.animateTo(0f) } },
                            ) { change, dragAmount ->
                                change.consume()
                                scope.launch { dismissOffset.snapTo((dismissOffset.value + dragAmount).coerceAtLeast(0f)) }
                            }
                        },
            ) { page ->
                ZoomableImagePage(
                    image = images[page],
                    label = openCodeGalleryImageLabel(page, count, images[page].name),
                    onZoomChange = { zoomed -> if (page == pagerState.currentPage) pageZoomed = zoomed },
                )
            }
            val chromePage = pagerState.currentPage.coerceIn(0, count - 1)
            ViewerChrome(
                title = "${chromePage + 1} of $count",
                name = images[chromePage].name,
                onDismiss = onDismiss,
                onShare = onShare?.let { share -> { share(chromePage) } },
            )
        }
    }
}

@Composable
private fun ViewerChrome(
    title: String,
    name: String?,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(OpenCodeIcons.Close, contentDescription = "Close", tint = Color.White)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = Color.White)
            if (!name.isNullOrBlank()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onShare != null) {
            TextButton(onClick = onShare) {
                Text(text = "Share", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        } else {
            // Keeps the title centred when there is no trailing action.
            Box(Modifier.size(48.dp))
        }
    }
}

/** One page: the whole picture letterboxed, zoomable by pinch or double tap and pannable once zoomed. */
@Composable
private fun ZoomableImagePage(
    image: OpenCodeGalleryImage,
    label: String,
    onZoomChange: (Boolean) -> Unit,
) {
    val state by rememberGalleryBitmap(image.id, VIEWER_DECODE_MAX_DIMENSION)
    var scale by remember(image.id) { mutableFloatStateOf(1f) }
    var offset by remember(image.id) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)

    fun clamp(candidate: Offset): Offset {
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun setScale(value: Float) {
        scale = value.coerceIn(1f, MAX_ZOOM)
        offset = if (scale == 1f) Offset.Zero else clamp(offset)
        currentOnZoomChange(scale > 1f)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = label }
                .onSizeChanged { size = it }
                .pointerInput(image.id) {
                    detectTapGestures(onDoubleTap = { setScale(if (scale > 1f) 1f else DOUBLE_TAP_ZOOM) })
                }.pointerInput(image.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            // One finger at 1× belongs to the pager and the dismiss drag.
                            if (pressed > 1 || scale > 1f) {
                                setScale(scale * event.calculateZoom())
                                offset = clamp(offset + event.calculatePan())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is GalleryBitmap.Ready ->
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                )
            GalleryBitmap.Loading -> Unit
            GalleryBitmap.Failed ->
                Text(
                    text = "Couldn't load ${image.name ?: "this image"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
        }
    }
}
