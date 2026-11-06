package dev.opencode.hexcode.designsystem.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

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

    /** An armed lock or drop disarms only once the finger is back under this. */
    val DisarmDistance = 52.dp

    /** Nothing shows for a drag shorter than this, either way. */
    val DeadZone = 12.dp

    /** A hold shorter than this is a tap. */
    const val TAP_MS = 380L

    /** The smoothed copy of the finger: critically damped, settling in about 120 ms. */
    const val FOLLOW_STIFFNESS = 2_400f
}

/** The talk button's drag, as pure functions of distances in pixels (app-plan.md, Dictation). */
object HexTalkDrag {
    /**
     * How far the look has come, 0 to 1, from the smoothed offset: nothing within [deadZone], full at [armAt]. A
     * negative [offset] is a drag to the left.
     */
    fun look(
        offset: Float,
        deadZone: Float,
        armAt: Float,
    ): Float = ((abs(offset) - deadZone) / (armAt - deadZone)).coerceIn(0f, 1f)

    /**
     * Lock or drop armed by the real finger at [dx], with hysteresis: armed at [armAt] either way, kept until the
     * finger is back under [disarmBelow]. Null when neither is armed.
     */
    fun arm(
        previous: HexTalkRelease?,
        dx: Float,
        armAt: Float,
        disarmBelow: Float,
    ): HexTalkRelease? =
        when {
            dx >= armAt -> HexTalkRelease.Lock
            dx <= -armAt -> HexTalkRelease.Drop
            previous == HexTalkRelease.Lock && dx >= disarmBelow -> HexTalkRelease.Lock
            previous == HexTalkRelease.Drop && dx <= -disarmBelow -> HexTalkRelease.Drop
            else -> null
        }
}

/**
 * A hold on the talk button, shared with what shows it elsewhere ([HexHoldHint]): whether the finger is down (and not
 * locked), the smoothed drag and what it has armed. The button writes it; anything else only reads it.
 */
@Stable
class HexTalkHold {
    internal val follow = Animatable(0f)
    internal var deadZone = 1f
    internal var armAt = 2f

    /** Held down, not locked. */
    var holding by mutableStateOf(false)
        internal set

    /** What letting go now would do: armed by the real finger, with hysteresis. */
    var armed by mutableStateOf<HexTalkRelease?>(null)
        internal set

    /** How far the smoothed drag has come, from −1 (drop) to 1 (lock), after the dead zone. */
    val lean: Float
        get() {
            val offset = follow.value
            return sign(offset) * HexTalkDrag.look(offset, deadZone, armAt)
        }
}

@Composable
fun rememberHexTalkHold(): HexTalkHold = remember { HexTalkHold() }

/**
 * The talk button (`.ptt`), the only voice meter on the screen: a 50 pt pill. Hold to talk; drag right to lock
 * (hands-free) or left to drop the take, then let go. The button answers the touch in the same frame with the
 * recording look and a light haptic; [onPress] starts the take and returns false when it doesn't.
 *
 * While dragging, the look follows a smoothed copy of the finger: a trace grows round the edge from the side dragged
 * to, a glow builds from that side and the lock or ✕ fades in at that end, all fading with the smoothed distance.
 * Arming is decided on the real finger, with hysteresis and one haptic tick. The button itself never moves. A tap on a
 * locked take ([onTapLocked]) pauses it.
 */
@Composable
fun HexTalkButton(
    state: HexTalkState,
    label: String,
    accessibilityLabel: String,
    enabled: Boolean,
    /** The take's meter level; null until its audio comes, while the bars run the listening wave. */
    level: () -> Float?,
    onPress: () -> Boolean,
    onRelease: (HexTalkRelease) -> Unit,
    onTapLocked: () -> Unit,
    modifier: Modifier = Modifier,
    hold: HexTalkHold = rememberHexTalkHold(),
    /** The take's clock (`0:12`), shown at the right while locked. */
    clock: String? = null,
    /** Screen readers: the "Drop" action's name; it shows while a take records. */
    dropActionLabel: String? = null,
    /** Screen readers' "Drop": forgets the take that's recording. */
    onDrop: () -> Unit = {},
) {
    val colors = HexTheme.colors
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // The gesture outlives state changes (pressing starts the take), so it reads the latest values.
    val currentState by rememberUpdatedState(state)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val tapLocked by rememberUpdatedState(onTapLocked)
    val drop by rememberUpdatedState(onDrop)
    // Held by the finger: the recording look shows from the touch itself, before the take's state comes back.
    var held by remember { mutableStateOf(false) }
    // The take came back as Holding; if it leaves Holding while still held (the microphone failed), the look goes back.
    var confirmed by remember { mutableStateOf(false) }
    SideEffect { if (state == HexTalkState.Holding) confirmed = true }
    val follow = hold.follow
    val density = LocalDensity.current
    val armAt = with(density) { HexTalkButtonDefaults.DragDistance.toPx() }
    val disarmBelow = with(density) { HexTalkButtonDefaults.DisarmDistance.toPx() }
    val deadZone = with(density) { HexTalkButtonDefaults.DeadZone.toPx() }
    hold.deadZone = deadZone
    hold.armAt = armAt
    val listening = state == HexTalkState.Holding || state == HexTalkState.Locked || (held && !confirmed)
    val holding = listening && state != HexTalkState.Locked
    val solid = state == HexTalkState.Idle && !listening
    // Into the recording look at once; back out with the usual fade.
    val fillSpec = if (listening) snap<Color>() else HexTheme.motion.fade()
    val fill by animateColorAsState(if (solid) colors.solid else colors.panel, fillSpec, label = "talk-fill")
    val content by animateColorAsState(if (solid) colors.onSolid else colors.text1, fillSpec, label = "talk-content")
    val meterAlpha by animateFloatAsState(if (hold.armed != null) 0.25f else 1f, HexTheme.motion.press(), label = "talk-armed")
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
                    // Without the hold: activating starts a hands-free take, or ends the one recording.
                    if (enabled) {
                        onClick {
                            if (currentState == HexTalkState.Locked) {
                                tapLocked()
                            } else if (press()) {
                                release(HexTalkRelease.Lock)
                            }
                            true
                        }
                    }
                    if (listening && dropActionLabel != null) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(dropActionLabel) {
                                    drop()
                                    true
                                },
                            )
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (currentState == HexTalkState.Locked) {
                            tapLocked()
                            return@awaitEachGesture
                        }
                        // Same frame as the touch: the look and a light haptic; the microphone starts alongside.
                        confirmed = false
                        held = true
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (!press()) {
                            held = false
                            return@awaitEachGesture
                        }
                        hold.holding = true
                        val startedAt = down.uptimeMillis
                        var dx = 0f
                        var lastUp = down.uptimeMillis
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            dx += change.positionChange().x
                            change.consume()
                            lastUp = change.uptimeMillis
                            val next = HexTalkDrag.arm(hold.armed, dx, armAt, disarmBelow)
                            if (next != null && next != hold.armed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            hold.armed = next
                            val target = dx
                            scope.launch {
                                follow.animateTo(target, spring(dampingRatio = 1f, stiffness = HexTalkButtonDefaults.FOLLOW_STIFFNESS))
                            }
                            if (!change.pressed) break
                        }
                        val how =
                            hold.armed ?: if (lastUp - startedAt < HexTalkButtonDefaults.TAP_MS) HexTalkRelease.Tap else HexTalkRelease.End
                        hold.armed = null
                        hold.holding = false
                        held = false
                        scope.launch { follow.snapTo(0f) }
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
            val offset = if (holding) follow.value else 0f
            val lock = if (offset > 0f) HexTalkDrag.look(offset, deadZone, armAt) else 0f
            val drop = if (offset < 0f) HexTalkDrag.look(offset, deadZone, armAt) else 0f
            if (lock > 0f) Glow(lock, direction = 1)
            if (drop > 0f) Glow(drop, direction = -1)
            val locked = state == HexTalkState.Locked
            HexMeter(
                level,
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (locked) 40.dp else 14.dp,
                        end =
                            if (locked &&
                                clock != null
                            ) {
                                56.dp
                            } else if (locked) {
                                16.dp
                            } else {
                                14.dp
                            },
                    )
                    // The prototype nudges the meter 6 px towards the side being dragged to.
                    .offset { IntOffset(((lock - drop) * 6.dp.toPx()).roundToInt(), 0) }
                    .alpha(if (holding) meterAlpha else 1f),
            )
            if (locked && clock != null) {
                Text(
                    clock,
                    style = HexTheme.type.mono,
                    color = colors.text3,
                    maxLines = 1,
                    // Screen readers hear it in the button's label instead.
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).semantics { hideFromAccessibility() },
                )
            }
            if (locked) {
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
            if (lock > 0f) Trace(lock, direction = 1, Modifier.fillMaxSize())
            if (drop > 0f) Trace(drop, direction = -1, Modifier.fillMaxSize())
            EndIcon(HexIcons.lock, colors.text1, lock, Modifier.align(Alignment.CenterEnd))
            EndIcon(HexIcons.x, colors.red, drop, Modifier.align(Alignment.CenterStart))
        }
    }
}

/** The lock or ✕ at the end being dragged to, fading and growing in with the drag. */
@Composable
private fun EndIcon(
    icon: ImageVector,
    tint: Color,
    progress: Float,
    modifier: Modifier,
) {
    if (progress <= 0f) return
    Icon(
        icon,
        null,
        tint = tint,
        modifier =
            modifier
                .padding(horizontal = 15.dp)
                .size(HexTheme.size.icon)
                .alpha(progress)
                .scale(0.7f + 0.3f * progress),
    )
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

/**
 * The trace round the button's edge. It starts at the middle of the end being dragged to and grows round both ways at
 * once, meeting at the other end when the drag reaches the lock point.
 */
@Composable
private fun Trace(
    progress: Float,
    direction: Int,
    modifier: Modifier = Modifier,
) {
    val color = if (direction > 0) HexTheme.colors.text1 else HexTheme.colors.red
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val radius = size.height / 2 - inset
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        // The outline from the right end's middle, up and round anticlockwise, back to the start.
        val outline =
            Path().apply {
                moveTo(right, size.height / 2)
                arcTo(Rect(right - 2 * radius, top, right, bottom), 0f, -90f, false)
                lineTo(left + radius, top)
                arcTo(Rect(left, top, left + 2 * radius, bottom), -90f, -180f, false)
                lineTo(right - radius, bottom)
                arcTo(Rect(right - 2 * radius, top, right, bottom), 90f, -90f, false)
            }
        val measure = PathMeasure().apply { setPath(outline, false) }
        val half = measure.length / 2 * progress
        val trace = Path()
        measure.getSegment(0f, half, trace, true)
        measure.getSegment(measure.length - half, measure.length, trace, true)
        // Dropping grows the same trace from the left end.
        scale(if (direction < 0) -1f else 1f, 1f) {
            drawPath(trace, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}
