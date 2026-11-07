package dev.opencode.hexcode.designsystem.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexMotion
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.designsystem.ReducedMotion

/** A [HexOption]'s leading mark: a radio (one choice), a checkbox (any), or the mic of a row you hold to dictate. */
enum class HexOptionMark { Radio, Checkbox, Mic }

/** A dictation take in a [HexOption]: recording while held, then being turned into text. */
enum class HexOptionTake { Listening, Transcribing }

/**
 * A full-width choice card (`.opt`): `panel2`, `line` border, radius `l`, padding 12 × 14. A leading 18 pt [mark],
 * the [title] (`button`) and an optional [detail] (`caption`, `text2`). Selected: border `tint(34)`, fill `tint(6)`;
 * a radio shows a `text1` ring with an 8 pt dot, a checkbox fills `text1` with a `check` in `onSolid`.
 *
 * During a [take] the second line is [takeLabel]: "Listening… · release to finish" with a red caret pulsing after it,
 * or "Transcribing…". With [onHoldStart] the card is held to dictate, as the talk button: the take starts on the press
 * (false refuses it) and [onHoldEnd] ends it on release; [onClick] is then only for screen readers.
 */
@Composable
fun HexOption(
    title: String,
    mark: HexOptionMark,
    selected: Boolean,
    modifier: Modifier = Modifier,
    detail: String? = null,
    take: HexOptionTake? = null,
    takeLabel: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onHoldStart: (() -> Boolean)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    val view = LocalView.current
    val start by rememberUpdatedState(onHoldStart)
    val end by rememberUpdatedState(onHoldEnd)
    var held by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            selected || take != null -> colors.tint(34f)
            held -> colors.line2
            else -> colors.line
        },
        HexTheme.motion.press(),
        label = "option-border",
    )
    val fill by animateColorAsState(
        if (selected ||
            take != null
        ) {
            colors.tint(6f)
        } else {
            colors.panel2
        },
        HexTheme.motion.press(),
        label = "option-fill",
    )
    val gesture =
        when {
            !enabled -> Modifier
            onHoldStart != null ->
                Modifier
                    .semantics {
                        role = Role.Button
                        onClick?.let { tap ->
                            this.onClick(label = null) {
                                tap()
                                true
                            }
                        }
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (start?.invoke() != true) return@awaitEachGesture
                            held = true
                            waitForUpOrCancellation()
                            held = false
                            end?.invoke()
                        }
                    }
            onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
            else -> Modifier
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.35f)
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape)
                .then(gesture)
                .semantics { this.selected = selected }
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.padding(top = 1.dp).size(18.dp), contentAlignment = Alignment.Center) {
            Mark(mark, selected, take == HexOptionTake.Listening)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = HexTheme.type.button, color = colors.text1)
            when {
                take != null && takeLabel != null -> TakeLine(take, takeLabel)
                detail != null -> Text(detail, style = HexTheme.type.caption, color = colors.text2)
            }
        }
    }
}

@Composable
private fun Mark(
    mark: HexOptionMark,
    selected: Boolean,
    listening: Boolean,
) {
    val colors = HexTheme.colors
    when (mark) {
        HexOptionMark.Radio ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(18.dp).border(1.5.dp, if (selected) colors.text1 else colors.text3, CircleShape),
            ) {
                if (selected) Box(Modifier.size(8.dp).background(colors.text1, CircleShape))
            }
        HexOptionMark.Checkbox -> {
            val box = RoundedCornerShape(HexTheme.radius.xs)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(18.dp)
                        .then(
                            if (selected) Modifier.background(colors.text1, box) else Modifier.border(1.5.dp, colors.text3, box),
                        ),
            ) {
                if (selected) Icon(HexIcons.check, null, tint = colors.onSolid, modifier = Modifier.size(12.dp))
            }
        }
        HexOptionMark.Mic ->
            Icon(HexIcons.mic, null, tint = if (listening) colors.red else colors.text2, modifier = Modifier.size(HexTheme.size.icon))
    }
}

/** "Listening… · release to finish" and a pulsing red caret, or "Transcribing…" with a spinner. */
@Composable
private fun TakeLine(
    take: HexOptionTake,
    label: String,
) {
    val colors = HexTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (take == HexOptionTake.Transcribing) HexSpinner(size = HexTheme.size.iconSmall)
        Text(label, style = HexTheme.type.caption, color = colors.text2)
        if (take == HexOptionTake.Listening) {
            val transition = rememberInfiniteTransition(label = "caret")
            val pulse by transition.animateFloat(
                1f,
                0.35f,
                infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
                label = "caret-alpha",
            )
            // A steady caret with Reduce Motion.
            val alpha = ReducedMotion.pulse(HexMotion.reduced, pulse)
            Box(
                Modifier
                    .width(2.dp)
                    .height(13.dp)
                    .alpha(alpha)
                    .background(colors.red),
            )
        }
    }
}
