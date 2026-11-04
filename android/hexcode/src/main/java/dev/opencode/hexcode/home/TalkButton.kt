package dev.opencode.hexcode.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.abs
import kotlin.math.sin

/** How far sideways a hold must go to lock or drop the take. */
val TalkDragDistance = 64.dp

/** A hold shorter than this is a tap: it locks the take instead of ending it. */
const val TALK_TAP_MS = 380L

/**
 * The talk button, the only voice meter on the screen. Hold to talk; drag right to lock (hands-free) or left to drop
 * the take, then let go: a trace round the button's edge fills as you drag and the action happens only when it is
 * complete. The button itself never moves. A tap on a locked take pauses it.
 */
@Composable
fun TalkButton(
    mode: TalkMode,
    label: String,
    accessibilityLabel: String,
    enabled: Boolean,
    level: () -> Float,
    onPress: () -> Unit,
    onRelease: (TalkRelease) -> Unit,
    onTapLocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listening = mode != TalkMode.Idle
    // The gesture outlives mode changes (pressing starts the take), so it reads the latest values.
    val currentMode by rememberUpdatedState(mode)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val tapLocked by rememberUpdatedState(onTapLocked)
    var progress by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableIntStateOf(0) }
    val drag = with(LocalDensity.current) { TalkDragDistance.toPx() }
    val slop = with(LocalDensity.current) { 8.dp.toPx() }
    val strong = openCodeColor("text-strong")
    val fill by animateColorAsState(
        if (listening) openCodeColor("surface-raised-base") else openCodeColor("button-primary-base"),
        tween(200),
        label = "talk-fill",
    )
    val shape = RoundedCornerShape(25.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(shape)
                .background(fill)
                .then(if (listening) Modifier.border(1.dp, openCodeColor("border-weak-base"), shape) else Modifier)
                .alpha(if (enabled) 1f else 0.45f)
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityLabel
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (currentMode == TalkMode.Locked) {
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
                                armed && direction > 0 -> TalkRelease.Lock
                                armed && direction < 0 -> TalkRelease.Drop
                                System.currentTimeMillis() - startedAt < TALK_TAP_MS -> TalkRelease.Tap
                                else -> TalkRelease.End
                            }
                        progress = 0f
                        direction = 0
                        release(how)
                    }
                },
    ) {
        if (!listening) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(OpenCodeIcons.Mic, null, tint = openCodeColor("icon-invert-base"), modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = openCodeColor("icon-invert-base"),
                )
            }
        } else {
            Meter(level, Modifier.fillMaxSize().padding(horizontal = 28.dp))
            if (mode == TalkMode.Locked) {
                Icon(
                    OpenCodeIcons.Lock,
                    null,
                    tint = strong,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 14.dp)
                            .size(14.dp),
                )
            }
            if (mode == TalkMode.Holding && progress > 0f) {
                Trace(progress, direction, Modifier.fillMaxSize())
                Icon(
                    if (direction > 0) OpenCodeIcons.Lock else OpenCodeIcons.Close,
                    null,
                    tint = if (direction > 0) strong else openCodeColor("error"),
                    modifier =
                        Modifier
                            .align(if (direction > 0) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 14.dp)
                            .size(16.dp)
                            .alpha(progress)
                            .scale(0.7f + 0.3f * progress),
                )
            }
        }
    }
}

/** The trace round the button's edge: it fills from the side being dragged to and is complete at the lock point. */
@Composable
private fun Trace(
    progress: Float,
    direction: Int,
    modifier: Modifier = Modifier,
) {
    val color = if (direction > 0) openCodeColor("text-strong") else openCodeColor("error")
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
        drawPath(trace, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Bars across the button that follow the take's loudness. */
@Composable
private fun Meter(
    level: () -> Float,
    modifier: Modifier = Modifier,
) {
    var smoothed by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now ->
                time = now
                smoothed += (level() - smoothed) * 0.25f
            }
        }
    }
    val color = openCodeColor("text-strong")
    Canvas(modifier) {
        val bars = 30
        val gap = size.width / bars
        val width = 2.5.dp.toPx()
        val seconds = time / 1000f
        for (i in 0 until bars) {
            val wave = 0.5f + 0.5f * sin(i * 0.7f + seconds * 8f)
            val height = size.height * (0.08f + smoothed.coerceIn(0f, 1f) * 0.5f * (0.4f + 0.6f * wave))
            val x = gap * i + gap / 2
            drawLine(
                color = color.copy(alpha = 0.55f + 0.45f * wave * smoothed),
                start = Offset(x, size.height / 2 - height / 2),
                end = Offset(x, size.height / 2 + height / 2),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}
