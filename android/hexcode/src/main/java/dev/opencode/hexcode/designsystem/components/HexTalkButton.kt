package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import kotlin.math.abs

/** What the talk button shows. */
enum class HexTalkState {
    /** Nothing recorded: `solid`, "Hold to talk". */
    Idle,

    /** A draft is waiting: outlined, "Hold to keep talking". */
    Draft,

    /** Held down: the meter; dragging sideways arms lock or drop. */
    Holding,

    /** Hands-free: the meter and a lock; a tap pauses. */
    Locked,
}

/** How a hold on the talk button ended. */
enum class HexTalkRelease {
    /** Dragged right past the lock point. */
    Lock,

    /** Dragged left past the drop point. */
    Drop,

    /** Let go after a hold. */
    End,

    /** Let go at once. */
    Tap,
}

object HexTalkButtonDefaults {
    /** How far sideways a hold must go to lock or drop the take. */
    val DragDistance = 64.dp

    /** A hold shorter than this is a tap. */
    const val TAP_MS = 380L
}

/**
 * The talk button (`.ptt`), the only voice meter on the screen: a 50 pt pill. Hold to talk; drag right to lock
 * (hands-free) or left to drop the take, then let go. As you drag, a trace runs round the button's edge, a glow
 * builds from that side and the lock or ✕ fades in at that end; the action happens only when the trace is complete.
 * The button itself never moves. A tap on a locked take ([onTapLocked]) pauses it.
 */
@Composable
fun HexTalkButton(
    state: HexTalkState,
    label: String,
    accessibilityLabel: String,
    enabled: Boolean,
    level: () -> Float,
    onPress: () -> Unit,
    onRelease: (HexTalkRelease) -> Unit,
    onTapLocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val listening = state == HexTalkState.Holding || state == HexTalkState.Locked
    // The gesture outlives state changes (pressing starts the take), so it reads the latest values.
    val currentState by rememberUpdatedState(state)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val tapLocked by rememberUpdatedState(onTapLocked)
    var progress by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableIntStateOf(0) }
    val drag = with(LocalDensity.current) { HexTalkButtonDefaults.DragDistance.toPx() }
    val slop = with(LocalDensity.current) { 8.dp.toPx() }
    val solid = state == HexTalkState.Idle
    val fill by animateColorAsState(if (solid) colors.solid else colors.panel, HexTheme.motion.fade(), label = "talk-fill")
    val content by animateColorAsState(if (solid) colors.onSolid else colors.text1, HexTheme.motion.fade(), label = "talk-content")
    val shape = RoundedCornerShape(percent = 50)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(shape)
                .background(fill)
                .then(if (!solid) Modifier.border(1.dp, colors.line2, shape) else Modifier)
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityLabel
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (currentState == HexTalkState.Locked) {
                            tapLocked()
                            return@awaitEachGesture
                        }
                        val startedAt = System.currentTimeMillis()
                        press()
                        var dx = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            dx += change.positionChange().x
                            change.consume()
                            direction = if (dx > 0) 1 else -1
                            progress = ((abs(dx) - slop) / drag).coerceIn(0f, 1f)
                            if (!change.pressed) break
                        }
                        val armed = progress >= 1f
                        val how =
                            when {
                                armed && direction > 0 -> HexTalkRelease.Lock
                                armed && direction < 0 -> HexTalkRelease.Drop
                                System.currentTimeMillis() - startedAt < HexTalkButtonDefaults.TAP_MS -> HexTalkRelease.Tap
                                else -> HexTalkRelease.End
                            }
                        progress = 0f
                        direction = 0
                        release(how)
                    }
                },
    ) {
        if (!listening) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HexIcons.mic, null, tint = content, modifier = Modifier.size(HexTheme.size.icon))
                Text(label, style = HexTheme.type.bodyStrong, color = content, maxLines = 1)
            }
        } else {
            val arming = state == HexTalkState.Holding && progress > 0f
            if (arming) Glow(progress, direction)
            HexMeter(
                level,
                Modifier
                    .fillMaxSize()
                    .padding(
                        start =
                            if (state ==
                                HexTalkState.Locked
                            ) {
                                40.dp
                            } else {
                                14.dp
                            },
                        end = if (state == HexTalkState.Locked) 16.dp else 14.dp,
                    ).alpha(if (arming && progress >= 1f) 0.25f else 1f),
            )
            if (state == HexTalkState.Locked) {
                Icon(
                    HexIcons.lock,
                    null,
                    tint = colors.text2,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 15.dp)
                            .size(HexTheme.size.icon),
                )
            }
            if (arming) {
                Trace(progress, direction, Modifier.fillMaxSize())
                Icon(
                    if (direction > 0) HexIcons.lock else HexIcons.x,
                    null,
                    tint = if (direction > 0) colors.text1 else colors.red,
                    modifier =
                        Modifier
                            .align(if (direction > 0) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 15.dp)
                            .size(HexTheme.size.icon)
                            .alpha(progress)
                            .scale(0.7f + 0.3f * progress),
                )
            }
        }
    }
}

/** The glow that builds inside the button from the side being dragged to. */
@Composable
private fun Glow(
    progress: Float,
    direction: Int,
) {
    val colors = HexTheme.colors
    val brush =
        if (direction > 0) {
            Brush.horizontalGradient(0.25f to Color.Transparent, 0.55f to colors.tint(4f), 1f to colors.tint(16f))
        } else {
            Brush.horizontalGradient(
                0f to colors.red.copy(alpha = 0.24f),
                0.45f to colors.red.copy(alpha = 0.06f),
                0.75f to Color.Transparent,
            )
        }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(progress)
            .background(brush),
    )
}

/** The trace round the button's edge: it fills from the side being dragged to and is complete at the lock point. */
@Composable
private fun Trace(
    progress: Float,
    direction: Int,
    modifier: Modifier = Modifier,
) {
    val color = if (direction > 0) HexTheme.colors.text1 else HexTheme.colors.red
    Canvas(modifier) {
        val inset = 1.dp.toPx()
        val radius = size.height / 2 - inset
        val outline =
            Path().apply {
                addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(radius)))
            }
        val measure = PathMeasure().apply { setPath(outline, false) }
        val trace = Path()
        measure.getSegment(0f, measure.length * progress, trace, true)
        // Dropping runs the same trace the other way round, from the left.
        scale(if (direction < 0) -1f else 1f, 1f) {
            drawPath(trace, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
