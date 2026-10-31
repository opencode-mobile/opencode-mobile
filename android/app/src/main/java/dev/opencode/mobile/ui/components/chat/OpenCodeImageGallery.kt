package dev.opencode.mobile.ui.components

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * One image in an [OpenCodeImageGallery] or [OpenCodeImageGalleryViewer]. [id] identifies the
 * image content (the same id always means the same picture); [name] is the file name shown in
 * captions, the failure tile, the viewer title and accessibility labels. The component loads
 * the picture through [LocalOpenCodeGalleryImageLoader]; it never receives bytes or URLs.
 */
@Immutable
data class OpenCodeGalleryImage(
    val id: String,
    val name: String? = null,
)

/** Loads gallery pictures by [OpenCodeGalleryImage.id]. The feature that owns the images provides it. */
interface OpenCodeGalleryImageLoader {
    /** A small preview already in memory (no I/O), painted while [load] runs. */
    fun preview(id: String): ByteArray? = null

    /** The full picture, or null when it cannot be loaded. May do I/O. */
    suspend fun load(id: String): ByteArray?
}

private object UnavailableGalleryImageLoader : OpenCodeGalleryImageLoader {
    override suspend fun load(id: String): ByteArray? = null
}

val LocalOpenCodeGalleryImageLoader =
    staticCompositionLocalOf<OpenCodeGalleryImageLoader> { UnavailableGalleryImageLoader }

/**
 * Mosaic by image count: one image keeps its own aspect ratio; two sit side by side; three are
 * one large tile and two stacked; four or more show a 2×2 grid whose last tile carries `+N`.
 */
enum class OpenCodeImageGalleryLayout(
    /** Tiles drawn; later images are reached through the overflow tile and the viewer. */
    val tileCount: Int,
) {
    Single(1),
    Pair(2),
    Feature(3),
    Grid(4),
    ;

    companion object {
        fun forCount(count: Int): OpenCodeImageGalleryLayout? =
            when {
                count <= 0 -> null
                count == 1 -> Single
                count == 2 -> Pair
                count == 3 -> Feature
                else -> Grid
            }

        /** `N` on the last grid tile: the images it covers, itself included. Zero when none hide. */
        fun overflowCount(count: Int): Int = if (count > Grid.tileCount) count - (Grid.tileCount - 1) else 0
    }
}

/** Accessibility label of a tile and a viewer page: `Image i of n, name`. */
fun openCodeGalleryImageLabel(
    index: Int,
    count: Int,
    name: String?,
): String {
    val position = "Image ${index + 1} of $count"
    return if (name.isNullOrBlank()) position else "$position, $name"
}

private val GalleryGap = 4.dp
private val FeatureHeight = 230.dp
private const val FEATURE_LEAD_WEIGHT = 1.4f
private val SinglePlaceholderHeight = 160.dp

/**
 * Inline image group in a timeline row. Tapping a tile calls [onImageClick] with that image's
 * index in [images] (the overflow tile opens its own index). A tile still loading shows a
 * shimmer; one that failed shows its name and does not respond to taps.
 */
@Composable
fun OpenCodeImageGallery(
    images: List<OpenCodeGalleryImage>,
    onImageClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = OpenCodeImageGalleryLayout.forCount(images.size) ?: return
    val count = images.size
    val tile: @Composable (index: Int, tileModifier: Modifier) -> Unit = { index, tileModifier ->
        val overflow = if (index == layout.tileCount - 1) OpenCodeImageGalleryLayout.overflowCount(count) else 0
        GalleryTile(
            image = images[index],
            label = openCodeGalleryImageLabel(index, count, images[index].name),
            overflow = overflow,
            showsCaption = layout != OpenCodeImageGalleryLayout.Single,
            onClick = { onImageClick(index) },
            modifier = tileModifier,
        )
    }
    when (layout) {
        OpenCodeImageGalleryLayout.Single ->
            SingleImage(
                images[0],
                openCodeGalleryImageLabel(0, 1, images[0].name),
                modifier,
            ) { onImageClick(0) }
        OpenCodeImageGalleryLayout.Pair ->
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
                tile(0, Modifier.weight(1f).aspectRatio(3f / 4f))
                tile(1, Modifier.weight(1f).aspectRatio(3f / 4f))
            }
        OpenCodeImageGalleryLayout.Feature ->
            Row(modifier.fillMaxWidth().height(FeatureHeight), horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
                tile(0, Modifier.weight(FEATURE_LEAD_WEIGHT).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GalleryGap)) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
        OpenCodeImageGalleryLayout.Grid ->
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GalleryGap)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
                    tile(0, Modifier.weight(1f).aspectRatio(4f / 3f))
                    tile(1, Modifier.weight(1f).aspectRatio(4f / 3f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GalleryGap)) {
                    tile(2, Modifier.weight(1f).aspectRatio(4f / 3f))
                    tile(3, Modifier.weight(1f).aspectRatio(4f / 3f))
                }
            }
    }
}

/** One image at its own aspect ratio, at most [OpenCodeMessageImageMaxHeight] tall, never cropped. */
@Composable
private fun SingleImage(
    image: OpenCodeGalleryImage,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val state by rememberGalleryBitmap(image.id, INLINE_DECODE_MAX_DIMENSION)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        when (val current = state) {
            is GalleryBitmap.Ready -> {
                val bitmap = current.bitmap
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val height = min(OpenCodeMessageImageMaxHeight.value, maxWidth.value / ratio).dp
                TileFrame(
                    label = label,
                    onClick = onClick,
                    modifier = Modifier.size(width = height * ratio, height = height),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            GalleryBitmap.Loading ->
                TileFrame(label = label, onClick = null, modifier = Modifier.fillMaxWidth().height(SinglePlaceholderHeight)) {
                    TileShimmer()
                }
            GalleryBitmap.Failed ->
                TileFrame(label = label, onClick = null, modifier = Modifier.fillMaxWidth().height(SinglePlaceholderHeight / 2)) {
                    FailedTile(image.name)
                }
        }
    }
}

@Composable
private fun GalleryTile(
    image: OpenCodeGalleryImage,
    label: String,
    overflow: Int,
    showsCaption: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val state by rememberGalleryBitmap(image.id, INLINE_DECODE_MAX_DIMENSION)
    val current = state
    // The overflow tile opens the viewer even before its own picture loads.
    val clickable = current is GalleryBitmap.Ready || overflow > 0
    TileFrame(
        label = if (overflow > 0) "$label, $overflow more" else label,
        onClick = if (clickable) onClick else null,
        modifier = modifier,
    ) {
        when (current) {
            is GalleryBitmap.Ready ->
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            GalleryBitmap.Loading -> TileShimmer()
            GalleryBitmap.Failed -> if (overflow == 0) FailedTile(image.name)
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier.fillMaxSize().background(OverflowScrim),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        } else if (showsCaption && current is GalleryBitmap.Ready && !image.name.isNullOrBlank()) {
            Caption(image.name, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun TileFrame(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(OpenCodeSmallShape)
                .background(openCodeColor("surface-raised-base"))
                .border(1.dp, openCodeColor("border-weaker-base"), OpenCodeSmallShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = "View full screen", role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).semantics(mergeDescendants = true) {
                    contentDescription = label
                    if (onClick != null) role = Role.Button
                },
        content = content,
    )
}

/** File name over a bottom gradient so it reads on any picture. */
@Composable
private fun Caption(
    name: String,
    modifier: Modifier,
) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, CaptionScrim)))
                .padding(start = 7.dp, end = 7.dp, top = 14.dp, bottom = 5.dp),
    )
}

@Composable
private fun FailedTile(name: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(OpenCodeIcons.Description, contentDescription = null, tint = openCodeColor("icon-weak-base"))
        Text(
            text = name?.takeIf { it.isNotBlank() } ?: "Image",
            style = MaterialTheme.typography.labelSmall,
            color = openCodeColor("text-weak"),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Couldn't load",
            style = MaterialTheme.typography.labelSmall,
            color = openCodeColor("text-weaker"),
        )
    }
}

@Composable
private fun TileShimmer() {
    val base = openCodeColor("surface-raised-base")
    val highlight = openCodeColor("text-weaker").copy(alpha = 0.14f)
    val phase = rememberShimmerPhase()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val value = phase?.value
                    if (value == null) {
                        drawRect(base)
                        return@drawBehind
                    }
                    val center = -SHIMMER_HALF_WIDTH + value * (1 + 2 * SHIMMER_HALF_WIDTH)
                    drawRect(
                        brush =
                            Brush.linearGradient(
                                colors = listOf(base, highlight, base),
                                start = Offset(size.width * (center - SHIMMER_HALF_WIDTH), 0f),
                                end = Offset(size.width * (center + SHIMMER_HALF_WIDTH), 0f),
                            ),
                    )
                },
    )
}

private const val SHIMMER_CYCLE_MS = 1_100L
private const val SHIMMER_HALF_WIDTH = 0.35f

/** 0…1 through one sweep, read in the draw phase; null (static fill) when animations are off. */
@Composable
private fun rememberShimmerPhase(): State<Float>? {
    val context = LocalContext.current
    val animationsOff =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }
    if (animationsOff) return null
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMillis ->
                phase.floatValue = (frameMillis % SHIMMER_CYCLE_MS).toFloat() / SHIMMER_CYCLE_MS
            }
        }
    }
    return phase
}

// The scrims sit on pictures, not on theme surfaces, so they stay dark in both themes.
private val OverflowScrim = Color.Black.copy(alpha = 0.55f)
private val CaptionScrim = Color.Black.copy(alpha = 0.7f)

internal sealed interface GalleryBitmap {
    data object Loading : GalleryBitmap

    data class Ready(
        val bitmap: Bitmap,
        /** False while only the in-memory preview is showing. */
        val isFull: Boolean,
    ) : GalleryBitmap

    data object Failed : GalleryBitmap
}

/**
 * The picture for [id] at [maxDimension]: the decoded cache first, then the loader's preview,
 * then the full image decoded off the main thread. Only composed tiles call this, so a long
 * gallery decodes what is on screen.
 */
@Composable
internal fun rememberGalleryBitmap(
    id: String,
    maxDimension: Int,
): State<GalleryBitmap> {
    val loader = LocalOpenCodeGalleryImageLoader.current
    val cached = remember(id, maxDimension) { OpenCodeImageBitmapCache.get(id, maxDimension) }
    return produceState(
        initialValue = cached?.let { GalleryBitmap.Ready(it, isFull = true) } ?: GalleryBitmap.Loading,
        id,
        maxDimension,
        loader,
    ) {
        if (cached != null) return@produceState
        val sharpest = OpenCodeImageBitmapCache.best(id)
        val preview =
            sharpest ?: loader.preview(id)?.let { bytes -> withContext(Dispatchers.Default) { decodeScaledBitmap(bytes, maxDimension) } }
        if (preview != null) value = GalleryBitmap.Ready(preview, isFull = false)
        val full =
            try {
                loader.load(id)?.let { bytes -> withContext(Dispatchers.Default) { decodeScaledBitmap(bytes, maxDimension) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        if (full != null) {
            OpenCodeImageBitmapCache.put(id, maxDimension, full)
            value = GalleryBitmap.Ready(full, isFull = true)
        } else if (preview == null) {
            value = GalleryBitmap.Failed
        }
    }
}
