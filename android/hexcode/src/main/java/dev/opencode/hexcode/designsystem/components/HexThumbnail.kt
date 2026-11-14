package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** How big a [HexThumbnail] is. */
enum class HexThumbnailSize(
    val side: Dp,
) {
    /** 40, radius `m`: the draft's images, with ✕ (the prototype's `.att-t`). */
    Regular(40.dp),

    /** 24, radius `xs`: a waiting message's images in Waiting to send. */
    Small(24.dp),
}

/**
 * A screenshot or photo as a rounded square (`.att-t`): [image] cropped to fill it, or, while it's [preparing], a faint
 * square with the spinner (no percentage). [dimmed] at 45 % when it won't be sent. With [onRemove], a small ✕ at its
 * top right corner removes it; the ✕'s touch area reaches past the badge.
 */
@Composable
fun HexThumbnail(
    image: Painter?,
    modifier: Modifier = Modifier,
    size: HexThumbnailSize = HexThumbnailSize.Regular,
    preparing: Boolean = false,
    dimmed: Boolean = false,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
) {
    val colors = HexTheme.colors
    val radius = if (size == HexThumbnailSize.Regular) HexTheme.radius.m else HexTheme.radius.xs
    val shape = RoundedCornerShape(radius)
    Box(modifier.size(size.side)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .alpha(if (dimmed) 0.45f else 1f)
                    .clip(shape)
                    .background(colors.tint(6f))
                    .border(1.dp, colors.line2, shape),
        ) {
            if (image != null && !preparing) {
                Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (preparing) HexSpinner(size = if (size == HexThumbnailSize.Regular) HexTheme.size.icon else HexTheme.size.iconSmall)
        }
        if (onRemove != null) RemoveBadge(onRemove, removeLabel)
    }
}

/**
 * A file as a rounded square (app-plan.md, Attach files › Draft row): the `doc` icon (a video's `play`) with its
 * [extension] in `mono` capitals under it, at most 4, on `tint(6)` with the `line` edge. The small size shows the icon
 * only. While it uploads, [progress] (0 to 1) is a ring on a dark scrim, as the prototype's `.att-t .ring`. [label] is
 * what a screen reader hears ("report.pdf, file").
 */
@Composable
fun HexFileThumbnail(
    extension: String,
    modifier: Modifier = Modifier,
    size: HexThumbnailSize = HexThumbnailSize.Regular,
    video: Boolean = false,
    progress: Float? = null,
    label: String? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
) {
    val colors = HexTheme.colors
    val regular = size == HexThumbnailSize.Regular
    val shape = RoundedCornerShape(if (regular) HexTheme.radius.m else HexTheme.radius.xs)
    Box(modifier.size(size.side).semantics(mergeDescendants = true) { label?.let { contentDescription = it } }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(colors.tint(6f))
                    .border(1.dp, colors.line, shape),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (video) HexIcons.play else HexIcons.doc,
                    null,
                    tint = colors.text2,
                    modifier = Modifier.size(if (regular) 16.dp else HexTheme.size.iconSmall),
                )
                if (regular && extension.isNotEmpty()) {
                    Text(
                        extension.take(4).uppercase(),
                        style = HexTheme.type.monoSmall.copy(fontSize = 8.5.sp, lineHeight = 10.sp),
                        color = colors.text3,
                        maxLines = 1,
                    )
                }
            }
            if (progress != null) UploadRing(progress, if (regular) 22.dp else 16.dp)
        }
        if (onRemove != null) RemoveBadge(onRemove, removeLabel)
    }
}

/** The upload ring: a white arc for [progress] (0 to 1) over a faint track, on a 50 % black scrim. */
@Composable
private fun UploadRing(
    progress: Float,
    diameter: Dp,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(
                Color.White,
                -90f,
                360f * progress.coerceIn(0f, 1f),
                false,
                Offset(inset, inset),
                arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** The ✕ badge: 17 pt, 5 pt past the top right corner; tapped anywhere in 28 pt around it. */
@Composable
private fun BoxScope.RemoveBadge(
    onRemove: () -> Unit,
    removeLabel: String?,
) {
    val colors = HexTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 11.dp, y = (-11).dp)
                .size(28.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { removeLabel?.let { contentDescription = it } },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(colors.panel3)
                    .border(1.dp, colors.line2, CircleShape),
        ) {
            Icon(HexIcons.x, null, tint = colors.text2, modifier = Modifier.size(9.dp))
        }
    }
}
