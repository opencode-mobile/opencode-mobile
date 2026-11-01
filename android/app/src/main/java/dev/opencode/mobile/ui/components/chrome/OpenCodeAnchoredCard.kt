package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.opencode.mobile.theme.OpenCodeLargeShape
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.max
import kotlin.math.min

/**
 * Where an anchored card sits: under its anchor with the leading edges lined up, [Gap] below it,
 * and at least [EdgeInset] from the trailing screen edge.
 */
object OpenCodeAnchoredCardLayout {
    /** Gap between a floating pill and its card: the same gap as between neighbouring pills. */
    val Gap = 8.dp

    /** Inset from the screen edge, the same as the Session Chat corner slots. */
    val EdgeInset = 16.dp

    /** Card corners, matching a floating pill's capsule. */
    val Shape = OpenCodeLargeShape

    /** Room the popup window keeps around the card so its shadow is not clipped. */
    internal val ShadowPadding = 8.dp

    /** Card width: the preferred width, or what fits between the anchor and the edge inset. */
    fun cardWidth(
        anchorLeft: Int,
        windowWidth: Int,
        preferredWidth: Int,
        edgeInset: Int,
    ): Int = min(preferredWidth, windowWidth - max(anchorLeft, 0) - edgeInset).coerceAtLeast(0)

    /** Top-left of the card in window pixels. */
    fun cardOffset(
        anchor: IntRect,
        gap: Int,
    ): IntOffset = IntOffset(max(anchor.left, 0), anchor.bottom + gap)
}

/**
 * A plain card anchored under the composable it is placed next to (inside the same Box), with no
 * pointing arrow. Back, a tap outside and TalkBack's dismiss gesture close it.
 */
@Composable
fun OpenCodeAnchoredCard(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorLeftPx: Int,
    windowWidthPx: Int,
    preferredWidth: Dp,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { OpenCodeAnchoredCardLayout.Gap.roundToPx() }
    val padPx = with(density) { OpenCodeAnchoredCardLayout.ShadowPadding.roundToPx() }
    val widthPx =
        OpenCodeAnchoredCardLayout.cardWidth(
            anchorLeft = anchorLeftPx,
            windowWidth = windowWidthPx,
            preferredWidth = with(density) { preferredWidth.roundToPx() },
            edgeInset = with(density) { OpenCodeAnchoredCardLayout.EdgeInset.roundToPx() },
        )
    val positionProvider =
        remember(gapPx, padPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val card = OpenCodeAnchoredCardLayout.cardOffset(anchorBounds, gapPx)
                    return IntOffset(card.x - padPx, card.y - padPx)
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.padding(OpenCodeAnchoredCardLayout.ShadowPadding)) {
            Surface(
                modifier = Modifier.width(with(density) { widthPx.toDp() }),
                shape = OpenCodeAnchoredCardLayout.Shape,
                color = openCodeColor("surface-raised-base"),
                border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
                shadowElevation = 6.dp,
            ) {
                content()
            }
        }
    }
}
