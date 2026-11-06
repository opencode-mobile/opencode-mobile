package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** Where a link tile leads, which sets its icons. */
enum class HexLinkKind {
    /** A page on the web: the globe, and ↗ before the host. It opens in the system browser. */
    Web,

    /** A page served by the computer the agent runs on: the monitor, before the computer's address. */
    Local,
}

/**
 * A link tile (`.fa.lnk`) under the status card: an icon in a 38 dp square, the [label] and, under it, the [host] it
 * opens, in a 56 dp panel at most 190 dp wide. [accessibilityLabel] is what a screen reader says ("Open Dev server").
 */
@Composable
fun HexLinkTile(
    label: String,
    host: String,
    kind: HexLinkKind,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) colors.panel2 else colors.panel, HexTheme.motion.fade(), label = "tile")
    val edge by animateColorAsState(if (pressed) colors.line2 else colors.line, HexTheme.motion.fade(), label = "tile-edge")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .height(56.dp)
                .widthIn(max = 190.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, edge, shape)
                .clickable(source, null, role = Role.Button, onClick = onClick)
                // One button for screen readers, read as "Open <label>".
                .clearAndSetSemantics { contentDescription = accessibilityLabel }
                .padding(start = 8.dp, end = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.tint(6f)),
        ) {
            Icon(
                if (kind == HexLinkKind.Local) HexIcons.monitor else HexIcons.globe,
                null,
                tint = colors.text2,
                modifier = Modifier.size(HexTheme.size.iconSmall),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = HexTheme.type.callout, color = colors.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(
                    if (kind == HexLinkKind.Local) HexIcons.monitor else HexIcons.out,
                    null,
                    tint = colors.text3,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    host,
                    style = HexTheme.type.footnote.copy(fontSize = 11.5.sp),
                    color = colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The card's row of tiles (`.fx-att`): [content] side by side, 8 dp apart, scrolling sideways, and fading out over its
 * last 36 dp while there is more to scroll to. Callers leave it out when there's nothing to show.
 */
@Composable
fun HexTileRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    if (scroll.canScrollForward) {
                        val fade = 36.dp.toPx()
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Black, Color.Transparent),
                                startX = size.width - fade,
                                endX = size.width,
                            ),
                            topLeft = Offset(size.width - fade, 0f),
                            size = Size(fade, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }.horizontalScroll(scroll),
        content = content,
    )
}
