package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator

/** Shared activity state for the working row and the reasoning row. */
enum class OpenCodeChatActivityState {
    /** Pulsing dot. The working row shows its label. Reasoning says "Thinking". */
    Working,

    /** Check mark. The working row shows its label. */
    Idle,

    /** Row height collapses to zero. */
    Collapsed,

    /** Visible settled row. Reasoning says "Thought" or "Thought · Ns". */
    Expanded,
}

/** Six-point circle that pulses while a turn is in progress. */
@Composable
fun OpenCodePulsingDot(modifier: Modifier = Modifier) {
    val pulse =
        rememberInfiniteTransition(label = "opencode-pulsing-dot").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "opencode-pulsing-dot-alpha",
        )
    Box(
        modifier =
            modifier
                .size(6.dp)
                .alpha(pulse.value)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
    )
}

/** Working, retrying, or compacting status row. Idle swaps the dot for a check. */
@Composable
fun OpenCodeWorkingIndicator(
    label: String,
    state: OpenCodeChatActivityState,
    modifier: Modifier = Modifier,
    fading: Boolean = false,
) {
    val collapsed = state == OpenCodeChatActivityState.Collapsed
    val animateOut = collapsed && label == WorkingTurnIndicator.IDLE_LABEL
    val collapseSpec = if (animateOut) tween<Float>(durationMillis = 220) else snap<Float>()
    val contentAlpha by animateFloatAsState(
        targetValue =
            when {
                collapsed -> 0f
                fading -> 0.35f
                else -> 1f
            },
        animationSpec = collapseSpec,
        label = "opencode-working-fade",
    )
    val heightFactor by animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = collapseSpec,
        label = "opencode-working-collapse",
    )
    Layout(
        modifier =
            modifier
                .fillMaxWidth()
                .clipToBounds()
                .semantics {
                    if (collapsed) {
                        hideFromAccessibility()
                    } else {
                        contentDescription = label
                    }
                },
        content = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
                        .alpha(contentAlpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeWorkingStatusMark(isIdle = markIsIdle(state, label))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val height = (placeable.height * heightFactor).toInt()
        layout(placeable.width, height) {
            placeable.placeRelative(0, 0)
        }
    }
}

private fun markIsIdle(
    state: OpenCodeChatActivityState,
    label: String,
): Boolean =
    when (state) {
        OpenCodeChatActivityState.Idle -> true
        OpenCodeChatActivityState.Collapsed -> WorkingTurnIndicator.showsIdleMark(label)
        OpenCodeChatActivityState.Working, OpenCodeChatActivityState.Expanded -> false
    }

@Composable
private fun OpenCodeWorkingStatusMark(isIdle: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (isIdle) 1f else 0f,
        animationSpec = if (isIdle) tween(durationMillis = 220) else snap(),
        label = "opencode-working-idle-mark",
    )
    Box(
        modifier = Modifier.size(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodePulsingDot(modifier = Modifier.alpha(1f - progress))
        Icon(
            imageVector = OpenCodeIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        alpha = progress
                        val scale = 0.4f + (0.6f * progress)
                        scaleX = scale
                        scaleY = scale
                    },
        )
    }
}
