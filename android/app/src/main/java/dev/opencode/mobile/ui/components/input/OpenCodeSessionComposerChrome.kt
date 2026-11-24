package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/** Fade at the end of the timeline: fits the resting gap, so it adds no space. */
internal val OpenCodeComposerRestingFadeHeight = OpenCodeBottomFadeOverhang

/** Fade while the reader is scrolled away from the end and content passes under the composer. */
internal val OpenCodeComposerScrolledFadeHeight = 46.dp

private const val COMPOSER_FADE_ANIMATION_MS = 200

/** Peak scrim opacity: the timeline stays visible as it scrolls around and below the composer. */
internal const val COMPOSER_SCRIM_OPACITY = 0.7f

internal fun openCodeComposerFadeHeight(awayFromEnd: Boolean): Dp =
    if (awayFromEnd) OpenCodeComposerScrolledFadeHeight else OpenCodeComposerRestingFadeHeight

/** Top corner radius of the docked composer. */
internal val OpenCodeComposerDockedCornerRadius = 22.dp

private const val COMPOSER_EDGE_BLEND = 0.12f

private val ComposerDockedShadowHeight = 14.dp

private const val COMPOSER_DOCKED_SHADOW_OPACITY = 0.1f

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
 * Dims the timeline around and just above an outlined composer: translucent `background-base`
 * over [composerHeight], fading out above it. The fade grows while [awayFromEnd]. Draw-only, so
 * touches and scrolling pass through. Place it above the list and below the floating pills.
 */
@Composable
fun OpenCodeComposerScrim(
    composerHeight: Dp,
    modifier: Modifier = Modifier,
    awayFromEnd: Boolean = false,
) {
    val fadeHeight by animateDpAsState(
        targetValue = openCodeComposerFadeHeight(awayFromEnd),
        animationSpec = tween(durationMillis = COMPOSER_FADE_ANIMATION_MS),
        label = "composerFadeHeight",
    )
    if (composerHeight <= 0.dp) return
    val background = openCodeColor("background-base")
    val totalHeight = composerHeight + fadeHeight
    val fadeEnd = fadeHeight / totalHeight
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
                                fadeEnd to background.copy(alpha = COMPOSER_SCRIM_OPACITY),
                                1f to background.copy(alpha = COMPOSER_SCRIM_OPACITY),
                            ),
                    ),
                ),
    )
}

/** Soft shadow above the docked composer. A permission request also gets its colored edge. */
internal fun Modifier.composerDockedSeparation(requestEdgeColor: Color?): Modifier =
    composerDockedTopShadow().then(requestEdgeColor?.let { Modifier.composerDockedTopEdge(it) } ?: Modifier)

/** Runs under the rounded corners so they do not show a lighter notch. */
private fun Modifier.composerDockedTopShadow(): Modifier =
    drawBehind {
        val shadowHeight = ComposerDockedShadowHeight.toPx()
        val radius = OpenCodeComposerDockedCornerRadius.toPx()
        drawRect(
            brush =
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = COMPOSER_DOCKED_SHADOW_OPACITY)),
                    startY = -shadowHeight,
                    endY = 0f,
                ),
            topLeft = Offset(0f, -shadowHeight),
            size = Size(size.width, shadowHeight + radius),
        )
    }

/** The docked composer's 1dp edge: along the top and around both top corners, no sides or bottom. */
private fun Modifier.composerDockedTopEdge(color: Color): Modifier =
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
