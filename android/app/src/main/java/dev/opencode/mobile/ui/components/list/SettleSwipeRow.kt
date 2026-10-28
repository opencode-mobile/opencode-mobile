package dev.opencode.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.home.HomeSettleFeed
import dev.opencode.mobile.domain.home.HomeSettleGesture
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.abs
import kotlin.math.roundToInt

private val SettleSwipeActionWidth = 84.dp
private val SettleSwipeOpenSlop = 56.dp
private val SettleSwipeCommitExtra = 36.dp

/**
 * Reveals [gestures] from the trailing edge. A long swipe commits the primary
 * action, which is the first gesture. An empty list draws [content] unchanged
 * so OpenCode rows stay free of settle chrome.
 */
@Composable
fun SettleSwipeRow(
    gestures: List<HomeSettleGesture>,
    onGesture: (HomeSettleGesture) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (gestures.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val actionWidthPx = with(density) { SettleSwipeActionWidth.toPx() }
    val openSlopPx = with(density) { SettleSwipeOpenSlop.toPx() }
    val commitExtraPx = with(density) { SettleSwipeCommitExtra.toPx() }
    val revealedPx = actionWidthPx * gestures.size
    var offset by remember(gestures) { mutableFloatStateOf(0f) }
    val primaryFill = openCodeColor("button-primary-base")
    val primaryIcon = openCodeColor("icon-invert-base")
    val secondaryIcon = openCodeColor("text-strong")
    val labelColor = openCodeColor("text-base")
    val rowBackground = openCodeColor("background-base")

    Box(modifier.fillMaxWidth().background(rowBackground)) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            gestures.forEachIndexed { index, gesture ->
                val primary = index == 0
                Column(
                    modifier =
                        Modifier
                            .width(SettleSwipeActionWidth)
                            .fillMaxHeight()
                            .clickable {
                                offset = 0f
                                onGesture(gesture)
                            },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(36.dp)
                                .background(
                                    if (primary) primaryFill else secondaryIcon.copy(alpha = 0.14f),
                                    CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = if (primary) primaryIcon else secondaryIcon
                        val icon = settleSwipeIcon(gesture)
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            SettleClockMark(color = tint, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        text = HomeSettleFeed.gestureLabel(gesture),
                        color = labelColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .offset { IntOffset(offset.roundToInt(), 0) }
                    .background(rowBackground)
                    .pointerInput(gestures) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                if (!dragging) {
                                    val total = change.position - down.position
                                    if (abs(total.x) > touchSlop && abs(total.x) > abs(total.y)) {
                                        dragging = true
                                    } else if (abs(total.y) > touchSlop) {
                                        break
                                    }
                                }
                                if (dragging) {
                                    change.consume()
                                    offset = (offset + delta.x).coerceIn(-(revealedPx + commitExtraPx), 0f)
                                }
                            }
                            if (!dragging) return@awaitEachGesture
                            val commitAt = -(revealedPx + commitExtraPx)
                            offset =
                                when {
                                    offset <= commitAt -> {
                                        gestures.firstOrNull()?.let(onGesture)
                                        0f
                                    }
                                    offset <= -openSlopPx -> -revealedPx
                                    else -> 0f
                                }
                        }
                    },
        ) {
            content()
        }
    }
}

/** Check for settle and wake, back for unsettle. Snooze draws a clock. */
private fun settleSwipeIcon(gesture: HomeSettleGesture): ImageVector? =
    when (gesture) {
        HomeSettleGesture.Settle, HomeSettleGesture.Wake -> OpenCodeIcons.Check
        HomeSettleGesture.Unsettle -> OpenCodeIcons.ArrowBack
        HomeSettleGesture.Snooze -> null
    }

@Composable
private fun SettleClockMark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val scale = size.minDimension / 16f
        val stroke = Stroke(width = 1.6f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color = color, radius = 5.2f * scale, style = stroke)
        drawPath(
            path =
                Path().apply {
                    moveTo(8f * scale, 5.2f * scale)
                    lineTo(8f * scale, 8f * scale)
                    lineTo(10f * scale, 9.4f * scale)
                },
            color = color,
            style = stroke,
        )
    }
}
