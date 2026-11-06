package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
 * While [level] is null (a take whose audio hasn't come yet) the bars run a gentle listening wave from the first frame,
 * and real levels blend in over [HexMeterMotion.BLEND_MS] once audio comes. [active] off leaves a flat row of short ticks.
 */
@Composable
fun HexMeter(
    level: () -> Float?,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    barWidth: Dp = 2.5.dp,
    /** How tall the bars can grow, centred in the space: 30 pt inside the 50 pt talk button. */
    band: Dp = 30.dp,
    gap: Dp = 3.dp,
) {
    var smoothed by remember { mutableFloatStateOf(0f) }
    // How long the meter has run (for the wave), and how long since audio came (null before).
    var time by remember { mutableLongStateOf(0L) }
    var sinceAudio by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(active) {
        var first = 0L
        var last = 0L
        var audioAt = -1L
        while (active) {
            withFrameMillis { now ->
                if (first == 0L) first = now
                val dt = if (last == 0L) 16L else now - last
                last = now
                time = now - first
                val target = level()
                if (target == null) {
                    audioAt = -1L
                    sinceAudio = null
                } else {
                    if (audioAt < 0L) {
                        // Audio just came: the real level starts from the wave's height, so nothing jumps.
                        audioAt = now
                        smoothed = HexMeterMotion.LISTENING_BASE
                    }
                    sinceAudio = now - audioAt
                    smoothed = HexMeterMotion.follow(smoothed, target.coerceIn(0f, 1f), dt)
                }
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
            val shown = HexMeterMotion.blend(HexMeterMotion.listening(i, time), smoothed, sinceAudio)
            val amplitude = if (active) shown * barFactor(i) * envelope else 0.05f
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

    /** Real levels blend in over this long once audio comes. */
    const val BLEND_MS = 80f

    /** The listening wave's middle height, about a third, and how far it swings either way. */
    const val LISTENING_BASE = 0.32f
    const val LISTENING_SWING = 0.1f

    /**
     * Bar [bar]'s height in the listening wave the bars run from the touch until audio comes, [timeMs] after the meter
     * started: about a third of the height, with a crest travelling left to right (as iOS).
     */
    fun listening(
        bar: Int,
        timeMs: Long,
    ): Float = LISTENING_BASE + LISTENING_SWING * sin(2 * PI * (timeMs / 1000.0 * 1.1 - bar / 14.0)).toFloat()

    /**
     * What a bar shows: the [listening] wave until audio comes ([sinceAudioMs] null), then [real] blending in over
     * [BLEND_MS].
     */
    fun blend(
        listening: Float,
        real: Float,
        sinceAudioMs: Long?,
    ): Float {
        if (sinceAudioMs == null) return listening
        val t = (sinceAudioMs / BLEND_MS).coerceIn(0f, 1f)
        return listening + (real - listening) * t
    }
}
