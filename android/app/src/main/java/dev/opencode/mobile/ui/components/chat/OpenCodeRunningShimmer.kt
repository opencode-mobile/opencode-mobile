package dev.opencode.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import dev.opencode.mobile.theme.openCodeColor

private const val RUNNING_CYCLE_MS = 1300
private const val RUNNING_SHIMMER_HALF_WIDTH = 0.3f

/**
 * 0…1 through one running cycle, derived from the shared frame clock rather
 * than a per-row animation, so rows that start running at different times
 * still sweep in step (iOS keys off wall-clock time the same way).
 */
@Composable
internal fun rememberRunningPhase(): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMillis ->
                phase.floatValue = (frameMillis % RUNNING_CYCLE_MS).toFloat() / RUNNING_CYCLE_MS
            }
        }
    }
    return phase
}

/**
 * A [highlightToken]-coloured band, `2 × RUNNING_SHIMMER_HALF_WIDTH` label widths wide,
 * sweeping left to right across `text-base` text.
 */
@Composable
internal fun runningShimmerBrush(
    phase: Float,
    highlightToken: String = "warning",
): ShaderBrush {
    val base = openCodeColor("text-base")
    val highlight = openCodeColor(highlightToken)
    val half = RUNNING_SHIMMER_HALF_WIDTH
    val center = -half + phase * (1 + 2 * half)
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader =
            LinearGradientShader(
                from = Offset(size.width * (center - half), 0f),
                to = Offset(size.width * (center + half), 0f),
                colors = listOf(base, highlight, base),
            )
    }
}
