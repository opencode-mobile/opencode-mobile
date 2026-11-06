package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        if (onRemove != null) {
            // 17 pt badge, 5 pt past the corner; tapped anywhere in 28 pt around it.
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
    }
}
