package dev.opencode.mobile.ui.components

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val FloatingPillShape = RoundedCornerShape(percent = 50)
private val FloatingPillPaddingHorizontal = 14.dp
private val FloatingPillPaddingVertical = 8.dp
private val FloatingPillMinHeight = 36.dp
private val FloatingPillIconSize = 16.dp
private val FloatingPillSpinnerSize = 14.dp
private val FloatingPillDotSize = 8.dp
private val FloatingPillIconSpacing = 6.dp
private val FloatingPillShadowElevation = 4.dp

/** What a floating pill shows. Every layout shares the btw pill height, type and chrome. */
sealed interface OpenCodeFloatingPillLayout {
    /** Visible label, or the spoken label for [IconOnly]. */
    val label: String

    /** Leading icon and a label, e.g. a branch or todos pill. */
    data class IconLabel(
        val icon: ImageVector,
        override val label: String,
    ) : OpenCodeFloatingPillLayout

    /** Round, icon only. [label] is what TalkBack speaks. */
    data class IconOnly(
        val icon: ImageVector,
        override val label: String,
    ) : OpenCodeFloatingPillLayout

    /**
     * Live counter such as "3 subagents". Shows a spinner while live; with [waiting],
     * needs attention swaps it for a pulsing dot.
     */
    data class Counter(
        override val label: String,
        val waiting: Boolean = false,
    ) : OpenCodeFloatingPillLayout

    /** Label with a trailing close ✕ that calls `onClose`. */
    data class Closable(
        override val label: String,
    ) : OpenCodeFloatingPillLayout
}

/** Where the thing a floating pill stands for is. */
enum class OpenCodeFloatingPillState(
    /** Spoken after the label. */
    val spokenName: String,
) {
    /** Work is running. */
    Live("In progress"),

    /** Something is ready to open. */
    Ready("Ready"),

    /** Waiting on the user: amber text and a thin amber ring. */
    NeedsAttention("Needs attention"),

    /** Something failed. */
    Error("Error"),

    /** Finished: a green check. */
    Done("Done"),
}

/** Leading mark of a floating pill, resolved from layout and state. */
internal sealed interface OpenCodeFloatingPillLeading {
    data object None : OpenCodeFloatingPillLeading

    data class Icon(
        val icon: ImageVector,
    ) : OpenCodeFloatingPillLeading

    data object Spinner : OpenCodeFloatingPillLeading

    data object PulsingDot : OpenCodeFloatingPillLeading

    data object DoneCheck : OpenCodeFloatingPillLeading
}

/** Semantic colour role of a floating pill's text and icon. */
internal enum class OpenCodeFloatingPillTone { Base, Strong, Warning, Error }

internal fun floatingPillLeading(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
): OpenCodeFloatingPillLeading {
    if (state == OpenCodeFloatingPillState.Done) return OpenCodeFloatingPillLeading.DoneCheck
    return when (layout) {
        is OpenCodeFloatingPillLayout.IconLabel -> OpenCodeFloatingPillLeading.Icon(layout.icon)
        is OpenCodeFloatingPillLayout.IconOnly -> OpenCodeFloatingPillLeading.Icon(layout.icon)
        is OpenCodeFloatingPillLayout.Counter ->
            when (state) {
                OpenCodeFloatingPillState.Live -> OpenCodeFloatingPillLeading.Spinner
                OpenCodeFloatingPillState.NeedsAttention ->
                    if (layout.waiting) OpenCodeFloatingPillLeading.PulsingDot else OpenCodeFloatingPillLeading.Spinner
                else -> OpenCodeFloatingPillLeading.None
            }
        is OpenCodeFloatingPillLayout.Closable -> OpenCodeFloatingPillLeading.None
    }
}

internal fun floatingPillTone(state: OpenCodeFloatingPillState): OpenCodeFloatingPillTone =
    when (state) {
        OpenCodeFloatingPillState.Live -> OpenCodeFloatingPillTone.Base
        OpenCodeFloatingPillState.Ready, OpenCodeFloatingPillState.Done -> OpenCodeFloatingPillTone.Strong
        OpenCodeFloatingPillState.NeedsAttention -> OpenCodeFloatingPillTone.Warning
        OpenCodeFloatingPillState.Error -> OpenCodeFloatingPillTone.Error
    }

internal fun floatingPillHasRing(state: OpenCodeFloatingPillState): Boolean = state == OpenCodeFloatingPillState.NeedsAttention

internal fun floatingPillDescription(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
): String = "${layout.label}, ${state.spokenName}"

internal fun floatingPillCloseDescription(layout: OpenCodeFloatingPillLayout): String = "Dismiss ${layout.label}"

/**
 * Shared floating pill for Session Chat: one look, four layouts, five states.
 * Without [onClick] the pill is a status, not a button.
 */
@Composable
fun OpenCodeFloatingPill(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val contentColor =
        when (floatingPillTone(state)) {
            OpenCodeFloatingPillTone.Base -> openCodeColor("text-base")
            OpenCodeFloatingPillTone.Strong -> openCodeColor("text-strong")
            OpenCodeFloatingPillTone.Warning -> openCodeColor("warning")
            OpenCodeFloatingPillTone.Error -> MaterialTheme.colorScheme.error
        }
    val borderColor = if (floatingPillHasRing(state)) openCodeColor("warning") else openCodeColor("border-weaker-base")
    val description = floatingPillDescription(layout, state)
    val iconOnly = layout is OpenCodeFloatingPillLayout.IconOnly

    Surface(
        modifier = modifier.defaultMinSize(minWidth = if (iconOnly) FloatingPillMinHeight else 0.dp, minHeight = FloatingPillMinHeight),
        shape = FloatingPillShape,
        color = openCodeColor("surface-raised-base").copy(alpha = 0.95f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp,
        shadowElevation = FloatingPillShadowElevation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .clip(FloatingPillShape)
                        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .then(
                            if (iconOnly) {
                                Modifier.size(FloatingPillMinHeight)
                            } else {
                                Modifier.padding(horizontal = FloatingPillPaddingHorizontal, vertical = FloatingPillPaddingVertical)
                            },
                        ),
                horizontalArrangement = Arrangement.spacedBy(FloatingPillIconSpacing, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingPillLeadingMark(floatingPillLeading(layout, state), contentColor)
                if (!iconOnly) {
                    Text(
                        text = layout.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (layout is OpenCodeFloatingPillLayout.Closable && onClose != null) {
                Icon(
                    OpenCodeIcons.Close,
                    contentDescription = floatingPillCloseDescription(layout),
                    tint = openCodeColor("icon-base"),
                    modifier =
                        Modifier
                            .clip(FloatingPillShape)
                            .clickable(role = Role.Button, onClick = onClose)
                            .padding(end = 10.dp, top = 10.dp, bottom = 10.dp, start = 2.dp)
                            .size(FloatingPillIconSize),
                )
            }
        }
    }
}

@Composable
private fun FloatingPillLeadingMark(
    leading: OpenCodeFloatingPillLeading,
    contentColor: Color,
) {
    when (leading) {
        OpenCodeFloatingPillLeading.None -> Unit
        is OpenCodeFloatingPillLeading.Icon ->
            Icon(leading.icon, contentDescription = null, modifier = Modifier.size(FloatingPillIconSize))
        OpenCodeFloatingPillLeading.Spinner -> FloatingPillSpinner(contentColor)
        OpenCodeFloatingPillLeading.PulsingDot -> FloatingPillPulsingDot()
        OpenCodeFloatingPillLeading.DoneCheck ->
            Icon(
                OpenCodeIcons.Check,
                contentDescription = null,
                tint = openCodeColor("success"),
                modifier = Modifier.size(FloatingPillIconSize),
            )
    }
}

/** The btw pill spinner; a still arc when animations are off. */
@Composable
private fun FloatingPillSpinner(color: Color) {
    if (rememberFloatingPillAnimationsOn()) {
        CircularProgressIndicator(
            modifier = Modifier.size(FloatingPillSpinnerSize),
            strokeWidth = 2.dp,
            color = color,
            trackColor = color.copy(alpha = 0.25f),
        )
    } else {
        CircularProgressIndicator(
            progress = { 0.75f },
            modifier = Modifier.size(FloatingPillSpinnerSize),
            strokeWidth = 2.dp,
            color = color,
            trackColor = color.copy(alpha = 0.25f),
        )
    }
}

/** Amber dot that pulses while an item waits for the user; still when animations are off. */
@Composable
private fun FloatingPillPulsingDot() {
    val alpha =
        if (rememberFloatingPillAnimationsOn()) {
            val pulse by rememberInfiniteTransition(label = "floatingPillDot").animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "floatingPillDotAlpha",
            )
            pulse
        } else {
            1f
        }
    Box(modifier = Modifier.size(FloatingPillIconSize), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(FloatingPillDotSize)
                    .alpha(alpha)
                    .background(openCodeColor("warning"), CircleShape),
        )
    }
}

/** False with the system animator scale at 0 (Remove animations). */
@Composable
private fun rememberFloatingPillAnimationsOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

@Preview(showBackground = true, name = "Floating Pill")
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Floating Pill Dark")
@Composable
private fun OpenCodeFloatingPillPreview() {
    OpenCodeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OpenCodeFloatingPillState.entries.forEach { state ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenCodeFloatingPill(OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.CallSplit, "+128 −14"), state, onClick = {})
                    OpenCodeFloatingPill(OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll to end"), state, onClick = {})
                    OpenCodeFloatingPill(OpenCodeFloatingPillLayout.Counter("3 subagents", waiting = true), state)
                }
            }
        }
    }
}
