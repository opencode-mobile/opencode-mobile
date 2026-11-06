package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The live level meter (`.meter`): thin rounded bars across the space, tallest in the middle, that follow [level]
 * (0 to 1, already mapped for show) while someone talks, rising fast and falling slowly. At 0 the bars rest as dots.
 * [active] off leaves a flat row of short ticks.
 */
@Composable
fun HexMeter(
    level: () -> Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    barWidth: Dp = 2.5.dp,
    /** How tall the bars can grow, centred in the space: 30 pt inside the 50 pt talk button. */
    band: Dp = 30.dp,
    gap: Dp = 3.dp,
) {
    var smoothed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        var last = 0L
        while (active) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 16L else now - last
                last = now
                smoothed = HexMeterMotion.follow(smoothed, level().coerceIn(0f, 1f), dt)
            }
        }
    }
    val colors = HexTheme.colors
    val color = if (active) colors.text1 else colors.text4
    Canvas(modifier.semantics { hideFromAccessibility() }) {
        val bw = barWidth.toPx()
        val tallest = band.toPx().coerceAtMost(size.height)
        val dot = RESTING_DOT.toPx()
        val space = gap.toPx()
        val n = max(3, floor((size.width + space) / (bw + space)).toInt())
        val offset = (size.width - (n * bw + (n - 1) * space)) / 2
        for (i in 0 until n) {
            val xn = if (n > 1) i / (n - 1f) else 0.5f
            val envelope = sin(PI * (0.08 + 0.84 * xn)).toFloat().pow(0.6f)
            // Silence rests as dots; each bar position has its own fixed factor, so it looks alive.
            val amplitude = if (active) smoothed * barFactor(i) * envelope else 0.05f
            val height = max(dot, (amplitude * tallest).coerceAtMost(tallest))
            drawRoundRect(
                color = color,
                topLeft = Offset(offset + i * (bw + space), (size.height - height) / 2),
                size = Size(bw, height),
                cornerRadius = CornerRadius(bw / 2),
            )
        }
    }
}

/** A bar at rest: a 3 pt dot. */
private val RESTING_DOT = 3.dp

/** Bar [i]'s fixed share of the level, from 0.88 to 1.12, the same for that position every time. */
internal fun barFactor(i: Int): Float {
    val x = sin(i * 12.9898) * 43_758.5453
    return 0.88f + 0.24f * (x - floor(x)).toFloat()
}

/** How the meter's bars move towards the level (app-plan.md, Dictation › Meter). */
object HexMeterMotion {
    /** Rising: about 40 ms. */
    const val ATTACK_MS = 40f

    /** Falling: about 220 ms. */
    const val RELEASE_MS = 220f

    /** [current] moved towards [target] over [dtMs]: fast up, slow down. */
    fun follow(
        current: Float,
        target: Float,
        dtMs: Long,
    ): Float {
        val tau = if (target > current) ATTACK_MS else RELEASE_MS
        return current + (target - current) * (1f - exp(-dtMs / tau))
    }
}
