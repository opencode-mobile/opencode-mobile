package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The live level meter (`.meter`): thin rounded bars across the space, tallest in the middle, that follow [level]
 * (0 to 1) while someone talks. [active] off leaves a flat row of short ticks.
 */
@Composable
fun HexMeter(
    level: () -> Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    barWidth: Dp = 2.5.dp,
    gap: Dp = 3.dp,
) {
    var smoothed by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while (active) {
            withFrameMillis { now ->
                time = now
                smoothed += (level().coerceIn(0f, 1f) - smoothed) * 0.25f
            }
        }
    }
    val colors = HexTheme.colors
    val color = if (active) colors.text1 else colors.text4
    Canvas(modifier.semantics { hideFromAccessibility() }) {
        val bw = barWidth.toPx()
        val space = gap.toPx()
        val n = max(3, floor((size.width + space) / (bw + space)).toInt())
        val offset = (size.width - (n * bw + (n - 1) * space)) / 2
        val t = time / 1000f
        for (i in 0 until n) {
            val xn = if (n > 1) i / (n - 1f) else 0.5f
            val envelope = sin(PI * (0.08 + 0.84 * xn)).toFloat().pow(0.6f)
            val amplitude = if (active) (0.15f + 0.85f * noise(i, t) * smoothed) * envelope else 0.05f
            val height = max(bw, amplitude * size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(offset + i * (bw + space), (size.height - height) / 2),
                size = Size(bw, height),
                cornerRadius = CornerRadius(bw / 2),
            )
        }
    }
}

/** The prototype's bar wobble: 0 to 1, different for each bar and moving with [t]. */
private fun noise(
    i: Int,
    t: Float,
): Float = abs(0.5f + 0.5f * sin(i * 12.9898f + t * 7.3f) * sin(i * 4.1f - t * 3.7f + sin(t * 1.3f + i)))
