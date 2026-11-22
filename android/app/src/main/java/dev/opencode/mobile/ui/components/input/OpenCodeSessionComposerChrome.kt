package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.featureflags.SessionComposerStyle
import dev.opencode.mobile.theme.openCodeColor

/** Height of the timeline fade above an outlined composer. */
val OpenCodeComposerFadeHeight = 46.dp

/** Top corner radius of the docked composer. */
internal val OpenCodeComposerDockedCornerRadius = 22.dp

private const val COMPOSER_EDGE_BLEND = 0.2f

/**
 * Extra room the last timeline row needs to rest clear of the composer's fade, on top of the
 * [OpenCodeBottomFadeOverhang] gap the timeline already keeps.
 */
fun openCodeComposerFadeClearance(style: SessionComposerStyle): Dp =
    when (style) {
        SessionComposerStyle.OutlineFade -> OpenCodeComposerFadeHeight - OpenCodeBottomFadeOverhang
        SessionComposerStyle.Floating,
        SessionComposerStyle.Docked,
        -> 0.dp
    }

/** Straight per-channel sRGB blend, so both platforms derive the same edge color. */
internal fun blendComposerEdge(
    base: Color,
    toward: Color,
    fraction: Float = COMPOSER_EDGE_BLEND,
): Color =
    Color(
        red = base.red + (toward.red - base.red) * fraction,
        green = base.green + (toward.green - base.green) * fraction,
        blue = base.blue + (toward.blue - base.blue) * fraction,
        alpha = base.alpha + (toward.alpha - base.alpha) * fraction,
    )

@Composable
internal fun openCodeComposerEdgeColor(): Color =
    blendComposerEdge(
        base = openCodeColor("surface-raised-base"),
        toward = openCodeColor("text-strong"),
    )

/**
 * Hides the timeline behind and just above an outlined composer: solid `background-base` over
 * [composerHeight], fading out across [OpenCodeComposerFadeHeight] above it. Draw-only, so
 * touches and scrolling pass through. Place it above the list and below the floating pills.
 */
@Composable
fun OpenCodeComposerScrim(
    composerHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (composerHeight <= 0.dp) return
    val background = openCodeColor("background-base")
    val totalHeight = composerHeight + OpenCodeComposerFadeHeight
    val fadeEnd = OpenCodeComposerFadeHeight / totalHeight
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(totalHeight)
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to background.copy(alpha = 0f),
                                fadeEnd to background,
                                1f to background,
                            ),
                    ),
                ),
    )
}

/** The docked composer's 1dp edge: along the top and around both top corners, no sides or bottom. */
internal fun Modifier.composerDockedTopEdge(color: Color): Modifier =
    drawWithCache {
        val strokeWidth = 1.dp.toPx()
        val inset = strokeWidth / 2
        val radius = OpenCodeComposerDockedCornerRadius.toPx()
        val arcSize = (radius - inset) * 2
        val path =
            Path().apply {
                moveTo(inset, radius)
                arcTo(Rect(inset, inset, inset + arcSize, inset + arcSize), 180f, 90f, false)
                lineTo(size.width - radius, inset)
                arcTo(
                    Rect(size.width - inset - arcSize, inset, size.width - inset, inset + arcSize),
                    270f,
                    90f,
                    false,
                )
            }
        onDrawWithContent {
            drawContent()
            drawPath(path, color, style = Stroke(width = strokeWidth))
        }
    }
