package dev.opencode.mobile.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A message's send time as the drag reveal shows it: the clock time, and the day
 * under it when the message is not from today.
 */
data class OpenCodeMessageTime(
    val time: String,
    val day: String?,
) {
    /** What TalkBack reads for the time: the gesture is invisible to it. */
    val spokenLabel: String
        get() = listOfNotNull(day, time).joinToString(", ")

    companion object {
        const val YESTERDAY = "Yesterday"

        /**
         * Time in the 12/24-hour style asked for. Day: null today, "Yesterday", the
         * weekday within the last week, otherwise a short date (with the year when
         * it is not this year's).
         */
        fun of(
            sentAtEpochMs: Long,
            nowEpochMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
            is24Hour: Boolean,
        ): OpenCodeMessageTime {
            val sent = Instant.ofEpochMilli(sentAtEpochMs).atZone(zone)
            val sentDay = sent.toLocalDate()
            val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
            val time = sent.format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale))
            val days = ChronoUnit.DAYS.between(sentDay, today)
            val day =
                when {
                    days <= 0 -> null
                    days == 1L -> YESTERDAY
                    days <= 6 -> sentDay.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                    else -> shortDate(sentDay, today, locale)
                }
            return OpenCodeMessageTime(time = time, day = day)
        }

        private fun shortDate(
            date: LocalDate,
            today: LocalDate,
            locale: Locale,
        ): String =
            if (date.year == today.year) {
                date.format(DateTimeFormatter.ofPattern("MMM d", locale))
            } else {
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            }
    }
}

/** Drag-to-reveal geometry in dp, shared by the gesture and its tests. */
object OpenCodeTimeRevealDrag {
    /** Furthest the content moves. */
    const val CAP = 72f

    /** The content follows the finger at this rate up to [LINEAR_LIMIT]. */
    const val FOLLOW = 0.9f
    const val LINEAR_LIMIT = 52f

    /** Rubber-band falloff past [LINEAR_LIMIT]. */
    const val FALLOFF = 30f

    /** Travel before the drag direction is decided. */
    const val DIRECTION_SLOP = 8f

    /** The time fades in over this offset range. */
    const val FADE_START = 12f
    const val FADE_LENGTH = 40f

    enum class Direction { Reveal, Other }

    /** How far the content moves left for a horizontal translation (negative is left). Right stays at 0. */
    fun offset(translation: Float): Float {
        val travel = max(0f, -translation) * FOLLOW
        if (travel <= LINEAR_LIMIT) return travel
        return LINEAR_LIMIT + (CAP - LINEAR_LIMIT) * (1f - exp(-(travel - LINEAR_LIMIT) / FALLOFF))
    }

    fun opacity(offset: Float): Float = min(1f, max(0f, (offset - FADE_START) / FADE_LENGTH))

    /** Null until the finger moved [DIRECTION_SLOP]; then Reveal for a mostly horizontal drag left. */
    fun direction(
        dx: Float,
        dy: Float,
    ): Direction? {
        if (hypot(dx, dy) <= DIRECTION_SLOP) return null
        return if (abs(dx) > abs(dy) * 1.2f && dx < 0f) Direction.Reveal else Direction.Other
    }
}

/** [OpenCodeMessageTime] for [sentAtEpochMs] in the device's locale and 12/24-hour setting. */
@Composable
fun rememberOpenCodeMessageTime(sentAtEpochMs: Long?): OpenCodeMessageTime? {
    if (sentAtEpochMs == null) return null
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(sentAtEpochMs, is24Hour) { OpenCodeMessageTime.of(sentAtEpochMs, is24Hour = is24Hour) }
}

/**
 * Drag [content] left to reveal [time] at its trailing edge; it springs back on
 * release. Vertical drags scroll the list, and drags that start in a system
 * gesture edge stay with the system.
 */
@Composable
fun OpenCodeTimeReveal(
    time: OpenCodeMessageTime?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (time == null) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetDp = remember { Animatable(0f) }
    var windowX by remember { mutableFloatStateOf(0f) }
    val windowWidth by rememberUpdatedState(LocalWindowInfo.current.containerSize.width)
    val gestureInsets by rememberUpdatedState(WindowInsets.systemGestures)
    val layoutDirection by rememberUpdatedState(LocalLayoutDirection.current)

    Box(modifier.onGloballyPositioned { windowX = it.positionInWindow().x }) {
        if (offsetDp.value > 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                OpenCodeMessageTimeLabel(
                    time = time,
                    modifier =
                        Modifier
                            .wrapContentWidth(align = Alignment.End, unbounded = true)
                            .alpha(OpenCodeTimeRevealDrag.opacity(offsetDp.value)),
                )
            }
        }
        Box(
            Modifier
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downX = windowX + down.position.x
                        val leftEdge = gestureInsets.getLeft(this@pointerInput, layoutDirection)
                        val rightEdge = gestureInsets.getRight(this@pointerInput, layoutDirection)
                        if (downX < leftEdge || downX > windowWidth - rightEdge) return@awaitEachGesture
                        // Lock only after the platform touch slop, so a sideways scroller inside
                        // the content (a code block) claims the drag first; its consumption hands
                        // the gesture over, even after the reveal has started.
                        val lockSlop = viewConfiguration.touchSlop
                        var total = Offset.Zero
                        var lockX: Float? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.isConsumed) break
                            total += change.positionChange()
                            if (lockX == null) {
                                val direction =
                                    OpenCodeTimeRevealDrag.direction(total.x.toDp().value, total.y.toDp().value)
                                        ?: continue
                                if (direction != OpenCodeTimeRevealDrag.Direction.Reveal) break
                                if (abs(total.x) <= lockSlop) continue
                                lockX = total.x
                            }
                            change.consume()
                            val target = OpenCodeTimeRevealDrag.offset((total.x - lockX).toDp().value)
                            scope.launch { offsetDp.snapTo(target) }
                        }
                        if (lockX != null || offsetDp.value > 0f) {
                            scope.launch { offsetDp.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 220f)) }
                        }
                    }
                }.offset { IntOffset(-with(density) { offsetDp.value.dp.roundToPx() }, 0) },
        ) {
            content()
        }
    }
}

@Composable
private fun OpenCodeMessageTimeLabel(
    time: OpenCodeMessageTime,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clearAndSetSemantics {},
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = time.time,
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
            maxLines = 1,
            textAlign = TextAlign.End,
        )
        time.day?.let { day ->
            Text(
                text = day,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("text-weak"),
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
    }
}
