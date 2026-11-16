package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme

/** What a [HexSpeechDial] shows (app-plan.md, Speech line and dial › The dial). */
enum class HexDialMode {
    /** The arc fills with the share downloaded, `text2`. */
    Downloading,

    /** The connection dropped, or the download hasn't started: the same in `text3`, never amber. */
    Stalled,

    /** A 28 % arc spinning once every 0.9 s. */
    Loading,

    /** The full ring, breathing (1.4 s). */
    WarmingUp,

    /** The full ring in `green`, with a check instead of the mic. */
    Ready,

    /** `red`, with "!" instead of the mic. */
    Failed,
}

/**
 * The speech model's dial: a ring with a small mic inside, drawn in a 24-unit box at [size] (14 in the draft's label,
 * 16 on the speech line, 60 in the sheet). A `tint` 13 % track and an arc 2 units wide with round caps, from
 * 12 o'clock; [progress] (0 to 1) is how much of the ring the arc fills, and moves linearly over 0.3 s. With Reduce
 * Motion nothing spins or breathes: loading and warming up show the full ring, still (an exception to the spinner rule).
 * It's decoration: the words beside it say what it shows, so screen readers skip it.
 */
@Composable
fun HexSpeechDial(
    mode: HexDialMode,
    progress: Float,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val still = HexMotion.reduced
    val track = colors.tint(13f)
    val color =
        when (mode) {
            HexDialMode.Downloading, HexDialMode.Loading, HexDialMode.WarmingUp -> colors.text2
            HexDialMode.Stalled -> colors.text3
            HexDialMode.Ready -> colors.green
            HexDialMode.Failed -> colors.red
        }
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300, easing = LinearEasing), label = "dial-progress")
    // Only what moves runs: the frame clock rests while the dial is still.
    val turn = if (mode == HexDialMode.Loading && !still) rememberLoop(0f, 360f, 900, LinearEasing, RepeatMode.Restart) else null
    val breath =
        if (mode == HexDialMode.WarmingUp && !still) rememberLoop(0.45f, 1f, 700, HexTheme.motion.easing, RepeatMode.Reverse) else null
    // Thinner when large, as the prototype's 60 pt dial (app-plan.md, Speech line and dial).
    val width = if (size >= 40.dp) WIDTH_LARGE else WIDTH
    Canvas(modifier.size(size)) {
        scale(this.size.width / GRID, pivot = Offset.Zero) {
            drawCircle(track, radius = RADIUS, center = CENTRE, style = Stroke(width))
            when (mode) {
                HexDialMode.Loading ->
                    if (turn == null) {
                        arc(width, color, 1f)
                    } else {
                        rotate(turn.value, CENTRE) { arc(width, color, SPIN_SHARE) }
                    }
                HexDialMode.WarmingUp -> arc(width, color.copy(alpha = breath?.value ?: 1f), 1f)
                HexDialMode.Ready -> arc(width, color, 1f)
                // Not on the phone yet: the track alone.
                HexDialMode.Stalled -> if (progress > 0f) arc(width, color, shown.coerceAtLeast(MIN_SHARE))
                HexDialMode.Downloading, HexDialMode.Failed -> arc(width, color, shown.coerceAtLeast(MIN_SHARE))
            }
            when (mode) {
                HexDialMode.Ready -> drawPath(Check, color, style = glyph(2.2f))
                HexDialMode.Failed -> {
                    drawPath(Bang, color, style = glyph(2.2f))
                    drawPath(BangDot, color, style = glyph(2.6f))
                }
                else ->
                    // The mic glyph, at 0.44 of the box around its centre, as the prototype's.
                    translate(CENTRE.x, CENTRE.y) {
                        scale(MIC_SCALE, pivot = Offset.Zero) {
                            translate(-CENTRE.x, -CENTRE.y) { drawPath(Mic, color, style = glyph(3.3f)) }
                        }
                    }
            }
        }
    }
}

/** A value going from [from] to [to] over [ms] for as long as it's composed; read it while drawing. */
@Composable
private fun rememberLoop(
    from: Float,
    to: Float,
    ms: Int,
    easing: Easing,
    repeat: RepeatMode,
): State<Float> =
    rememberInfiniteTransition(
        label = "dial",
    ).animateFloat(from, to, infiniteRepeatable(tween(ms, easing = easing), repeat), label = "dial-loop")

private fun DrawScope.arc(
    width: Float,
    color: Color,
    share: Float,
) = drawArc(
    color,
    startAngle = -90f,
    sweepAngle = 360f * share,
    useCenter = false,
    topLeft = Offset(CENTRE.x - RADIUS, CENTRE.y - RADIUS),
    size = Size(2 * RADIUS, 2 * RADIUS),
    style = Stroke(width, cap = StrokeCap.Round),
)

private fun glyph(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun path(vararg data: String): Path = Path().apply { data.forEach { addPath(PathParser().parsePathString(it).toPath()) } }

private const val GRID = 24f
private const val RADIUS = 10f
private const val WIDTH = 2f
private const val WIDTH_LARGE = 1.6f
private const val SPIN_SHARE = 0.28f

/** A sliver shows from the start, so a download that has just begun still reads as one. */
private const val MIN_SHARE = 0.02f
private const val MIC_SCALE = 0.44f
private val CENTRE = Offset(12f, 12f)

private val Mic = path("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0M12 18v3")
private val Check = path("m7.6 12.4 3 3 5.8-6.2")
private val Bang = path("M12 7.5v5.2")
private val BangDot = path("M12 16.4h.01")

/** The dial's sizes: in the draft's label, on the speech line, and in the sheet. */
object HexDialSize {
    val Label = 14.dp
    val Line = 16.dp
    val Sheet = 60.dp
}
