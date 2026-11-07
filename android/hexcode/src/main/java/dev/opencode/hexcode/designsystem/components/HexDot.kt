package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.ReducedMotion

/** What a [HexDot] says. Red and syncing pulse. */
enum class HexDotTone { Neutral, Green, Amber, Red, Syncing }

/** The 6 pt status dot (`.dot`). */
@Composable
fun HexDot(
    tone: HexDotTone = HexDotTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val color =
        when (tone) {
            HexDotTone.Neutral, HexDotTone.Syncing -> colors.text3
            HexDotTone.Green -> colors.green
            HexDotTone.Amber -> colors.amber
            HexDotTone.Red -> colors.red
        }
    val pulse =
        when (tone) {
            HexDotTone.Red -> 1200
            HexDotTone.Syncing -> 1000
            else -> 0
        }
    // With Reduce Motion the pulse rests at full colour, not wherever Compose would end it.
    val reduced = HexMotion.reduced
    val alpha =
        if (pulse > 0 && !reduced) {
            val transition = rememberInfiniteTransition(label = "dot")
            val value by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(pulse / 2, easing = LinearEasing), RepeatMode.Reverse),
                label = "dot-pulse",
            )
            value
        } else {
            1f
        }
    Box(
        modifier
            .size(6.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** Shrinks what it modifies a little while [source] is pressed, as the prototype's `:active` does. */
@Composable
internal fun Modifier.pressScale(
    source: MutableInteractionSource,
    pressed: Float,
): Modifier {
    val isPressed by source.collectIsPressedAsState()
    // Colour only with Reduce Motion: no scale.
    val reduced = HexMotion.reduced
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (isPressed) ReducedMotion.pressScale(reduced, pressed) else 1f,
        HexTheme.motion.press(),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
