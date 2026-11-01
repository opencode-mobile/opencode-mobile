package dev.opencode.mobile.ui.components

import android.content.ContentResolver
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val FloatingPillShape = RoundedCornerShape(percent = 50)
private val FloatingPillPaddingHorizontal = 14.dp
private val FloatingPillPaddingVertical = 8.dp
internal val FloatingPillMinHeight = 36.dp
private val FloatingPillIconSize = 16.dp
private val FloatingPillSpinnerSize = 14.dp
private val FloatingPillDotSize = 8.dp
private val FloatingPillIconSpacing = 6.dp
private val FloatingPillShadowElevation = 4.dp
private val FloatingPillCloseSlotWidth = 28.dp
private val FloatingPillCloseTouchTarget = 48.dp

// Centring the 16 dp icon in the 28 dp slot puts it 6 dp in; the btw pill draws it 2 dp in.
private val FloatingPillCloseIconShift = 4.dp

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

    /** Plain short label with no mark and no close, e.g. a "+3" overflow button. */
    data class Label(
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

    /** Compact fallback for layouts without a mark: a small dot in the state's tone. */
    data object StateDot : OpenCodeFloatingPillLeading
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
        is OpenCodeFloatingPillLayout.Closable, is OpenCodeFloatingPillLayout.Label -> OpenCodeFloatingPillLeading.None
    }
}

/** Mark a compact pill draws in its round body: the regular mark, or a state dot when there is none. */
internal fun floatingPillCompactLeading(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
): OpenCodeFloatingPillLeading =
    floatingPillLeading(layout, state).takeUnless { it == OpenCodeFloatingPillLeading.None }
        ?: OpenCodeFloatingPillLeading.StateDot

/** Whether the pill shows its ✕: closable layouts with a close action, and never when compact. */
internal fun floatingPillShowsClose(
    layout: OpenCodeFloatingPillLayout,
    compact: Boolean,
    hasCloseAction: Boolean,
): Boolean = layout is OpenCodeFloatingPillLayout.Closable && hasCloseAction && !compact

/** Live and waiting counters are a polite live region. Other pills stay quiet. */
internal fun floatingPillAnnouncesUpdates(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
): Boolean =
    layout is OpenCodeFloatingPillLayout.Counter &&
        (state == OpenCodeFloatingPillState.Live || state == OpenCodeFloatingPillState.NeedsAttention)

internal fun floatingPillTone(state: OpenCodeFloatingPillState): OpenCodeFloatingPillTone =
    when (state) {
        OpenCodeFloatingPillState.Live -> OpenCodeFloatingPillTone.Base
        OpenCodeFloatingPillState.Ready, OpenCodeFloatingPillState.Done -> OpenCodeFloatingPillTone.Strong
        OpenCodeFloatingPillState.NeedsAttention -> OpenCodeFloatingPillTone.Warning
        OpenCodeFloatingPillState.Error -> OpenCodeFloatingPillTone.Error
    }

internal fun floatingPillHasRing(state: OpenCodeFloatingPillState): Boolean = state == OpenCodeFloatingPillState.NeedsAttention

/** What the pill speaks: the caller's [override], or "label, state". */
internal fun floatingPillDescription(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
    override: String? = null,
): String = override ?: "${layout.label}, ${state.spokenName}"

internal fun floatingPillCloseDescription(
    layout: OpenCodeFloatingPillLayout,
    override: String? = null,
): String = "Dismiss ${override ?: layout.label}"

/**
 * Shared floating pill for Session Chat: one look, five layouts, five states.
 * Without [onClick] the pill is a status, not a button. [compact] shrinks it to its round
 * mark (icon, spinner, dot or check) and hides the text and ✕, still speaking the full
 * label. [accessibilityLabel] replaces the default "label, state" speech.
 */
@Composable
fun OpenCodeFloatingPill(
    layout: OpenCodeFloatingPillLayout,
    state: OpenCodeFloatingPillState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    accessibilityLabel: String? = null,
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
    val description = floatingPillDescription(layout, state, accessibilityLabel)
    val announcesUpdates = floatingPillAnnouncesUpdates(layout, state)
    val round = compact || layout is OpenCodeFloatingPillLayout.IconOnly
    val leading = if (compact) floatingPillCompactLeading(layout, state) else floatingPillLeading(layout, state)

    // The surface sits behind the content instead of wrapping it, so its clip doesn't cut
    // the ✕'s 48 dp touch target down to the 36 dp pill.
    Box(
        modifier = modifier.defaultMinSize(minWidth = if (round) FloatingPillMinHeight else 0.dp, minHeight = FloatingPillMinHeight),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = FloatingPillShape,
            color = openCodeColor("surface-raised-base").copy(alpha = 0.95f),
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 2.dp,
            shadowElevation = FloatingPillShadowElevation,
        ) {}
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier =
                        Modifier
                            .clip(FloatingPillShape)
                            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                            // Only the description is spoken: clearing drops the visible label and
                            // spinner below it, so TalkBack doesn't repeat them after a custom label.
                            // The click action and role above stay on the same node.
                            .clearAndSetSemantics {
                                contentDescription = description
                                if (announcesUpdates) liveRegion = LiveRegionMode.Polite
                            }.then(
                                if (round) {
                                    Modifier.size(FloatingPillMinHeight)
                                } else {
                                    Modifier.padding(horizontal = FloatingPillPaddingHorizontal, vertical = FloatingPillPaddingVertical)
                                },
                            ),
                    horizontalArrangement = Arrangement.spacedBy(FloatingPillIconSpacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingPillLeadingMark(leading, contentColor)
                    if (!round) {
                        Text(
                            text = layout.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onClose != null && floatingPillShowsClose(layout, compact, hasCloseAction = true)) {
                    FloatingPillClose(
                        description = floatingPillCloseDescription(layout, accessibilityLabel),
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

/** The ✕: drawn in a 28 × 36 dp slot, tappable across a 48 dp square centred on it. */
@Composable
private fun FloatingPillClose(
    description: String,
    onClose: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .floatingPillTouchTarget(FloatingPillCloseSlotWidth, FloatingPillMinHeight, FloatingPillCloseTouchTarget)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = FloatingPillMinHeight / 2),
                    role = Role.Button,
                    onClick = onClose,
                ).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            OpenCodeIcons.Close,
            contentDescription = null,
            tint = openCodeColor("icon-base"),
            // Same spot as the btw pill's ✕: 2 dp in from the slot's start, 10 dp from its end.
            modifier = Modifier.offset(x = -FloatingPillCloseIconShift).size(FloatingPillIconSize),
        )
    }
}

/** Measures at [target] × [target] but lays out as [width] × [height], centred, so the touch area outgrows the visual slot. */
private fun Modifier.floatingPillTouchTarget(
    width: Dp,
    height: Dp,
    target: Dp,
): Modifier =
    layout { measurable, _ ->
        val size = target.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(size, size))
        val slotWidth = width.roundToPx()
        val slotHeight = height.roundToPx()
        layout(slotWidth, slotHeight) {
            placeable.place((slotWidth - size) / 2, (slotHeight - size) / 2)
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
        OpenCodeFloatingPillLeading.StateDot ->
            Box(modifier = Modifier.size(FloatingPillIconSize), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(FloatingPillDotSize).background(contentColor, CircleShape))
            }
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

/** False with the system animator scale at 0 (Remove animations); follows the setting while shown. */
@Composable
private fun rememberFloatingPillAnimationsOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var animationsOn by remember(resolver) { mutableStateOf(floatingPillAnimationsOn(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    animationsOn = floatingPillAnimationsOn(resolver)
                }
            }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return animationsOn
}

private fun floatingPillAnimationsOn(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

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
