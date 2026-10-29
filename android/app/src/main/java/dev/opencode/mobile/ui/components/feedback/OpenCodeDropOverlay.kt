package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Whether the items being dragged can be dropped. */
enum class OpenCodeDropOverlayVariant {
    /** At least one item will be attached. */
    Accept,

    /** Nothing in the drag can be attached; the platform drop indicator shows no-drop. */
    Reject,
}

/**
 * Drag-over state for a drop target that covers a whole screen: the content dims and a centred
 * dashed card says what a drop would do. [previews] are the kinds of the first items that will be
 * accepted (at most four are shown); a [OpenCodeDropOverlayVariant.Reject] card shows none.
 */
@Composable
fun OpenCodeDropOverlay(
    variant: OpenCodeDropOverlayVariant,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    previews: List<OpenCodeAttachmentVariant> = emptyList(),
) {
    val accent = if (variant == OpenCodeDropOverlayVariant.Accept) openCodeColor("interactive") else openCodeColor("error")
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = DROP_OVERLAY_SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 32.dp)
                    .widthIn(max = 420.dp)
                    .background(openCodeColor("surface-raised-base"), DropCardShape)
                    .dashedBorder(accent)
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "$title. $subtitle"
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (variant == OpenCodeDropOverlayVariant.Accept) OpenCodeIcons.ArrowDownward else OpenCodeIcons.Close,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp),
            )
            if (variant == OpenCodeDropOverlayVariant.Accept && previews.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    previews.take(MAX_DROP_PREVIEWS).forEach { DropPreviewTile(it) }
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (variant == OpenCodeDropOverlayVariant.Accept) openCodeColor("text-strong") else accent,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-base"),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DropPreviewTile(variant: OpenCodeAttachmentVariant) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .background(openCodeColor("background-base"), OpenCodeSmallShape)
                .border(1.dp, openCodeColor("border-weak-base"), OpenCodeSmallShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (variant == OpenCodeAttachmentVariant.Image) "IMG" else variant.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = openCodeColor("text-weak"),
        )
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier =
    drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(DropCardRadius.toPx()),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
        )
    }

private const val DROP_OVERLAY_SCRIM_ALPHA = 0.32f
private const val MAX_DROP_PREVIEWS = 4
private val DropCardRadius = 22.dp
private val DropCardShape = RoundedCornerShape(DropCardRadius)

@Preview(showBackground = true, heightDp = 480)
@Composable
private fun OpenCodeDropOverlayAcceptPreview() {
    OpenCodeTheme {
        OpenCodeDropOverlay(
            variant = OpenCodeDropOverlayVariant.Accept,
            title = "Drop to attach 2 files",
            subtitle = "1 will be skipped · Folders can't be attached.",
            previews = listOf(OpenCodeAttachmentVariant.Image, OpenCodeAttachmentVariant.Pdf),
        )
    }
}

@Preview(showBackground = true, heightDp = 480)
@Composable
private fun OpenCodeDropOverlayRejectPreview() {
    OpenCodeTheme {
        OpenCodeDropOverlay(
            variant = OpenCodeDropOverlayVariant.Reject,
            title = "Can't attach this",
            subtitle = "Folders can't be attached.",
        )
    }
}
