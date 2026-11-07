package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** A [HexGoalLine]'s look: no goal, active (working or waiting), paused, or done. */
enum class HexGoalLineState { Empty, Active, Waiting, Paused, Done }

/**
 * Today's goal in one 34 pt row under the top bar (`.goalline`): the `target` icon (14 pt), the [text] (`callout`,
 * `text1`, one line, ellipsised), and a quiet [suffix] (`mono`, `text3`): "round 3", "waiting", "paused", "done". Empty
 * ("Set today's goal") is all `text3`; the icon is `green` while active, waiting or done, `text3` when paused. A tap
 * opens the goal sheet.
 */
@Composable
fun HexGoalLine(
    state: HexGoalLineState,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
) {
    val colors = HexTheme.colors
    val icon =
        when (state) {
            HexGoalLineState.Empty, HexGoalLineState.Paused -> colors.text3
            HexGoalLineState.Active, HexGoalLineState.Waiting, HexGoalLineState.Done -> colors.green
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(34.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { suffix?.let { stateDescription = it } },
    ) {
        Icon(HexIcons.target, null, tint = icon, modifier = Modifier.size(14.dp))
        Text(
            text,
            style = HexTheme.type.callout,
            color = if (state == HexGoalLineState.Empty) colors.text3 else colors.text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (suffix != null) Text(suffix, style = HexTheme.type.mono, color = colors.text3, maxLines = 1)
    }
}

/** A [HexGoalBox]'s state: no goal, the goal set, or a take: Listening, Transcribing, then its text (filled). */
enum class HexGoalBoxState { Empty, Set, Listening, Transcribing, Filled }

/**
 * The goal sheet's text box (`.brief-box`): `background`, a `line` border (`line2` while a take is live), radius `l`.
 * A label row (`mono`, `text3`): [label] left ("/goal", "new goal") and [status] right ("active · round 3 of 50", "On
 * this phone"); then the [text] (`body`, `text1`), and an optional [reason] (`caption`, `text2`). Empty shows only the
 * [text] in `text3` ("No goal yet."). Listening puts a red caret after the text ("Listening…"); Transcribing a spinner
 * before it.
 */
@Composable
fun HexGoalBox(
    state: HexGoalBoxState,
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    status: String? = null,
    reason: String? = null,
) {
    val colors = HexTheme.colors
    val shape = RoundedCornerShape(HexTheme.radius.l)
    val live = state == HexGoalBoxState.Listening || state == HexGoalBoxState.Transcribing
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.background)
                .border(1.dp, if (live) colors.line2 else colors.line, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (state != HexGoalBoxState.Empty && (label != null || status != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label.orEmpty(), style = HexTheme.type.mono, color = colors.text3, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (status != null) Text(status, style = HexTheme.type.mono, color = colors.text3, maxLines = 1)
            }
        }
        when (state) {
            HexGoalBoxState.Empty -> Text(text, style = HexTheme.type.row, color = colors.text3)
            HexGoalBoxState.Set, HexGoalBoxState.Filled -> Text(text, style = HexTheme.type.body, color = colors.text1)
            HexGoalBoxState.Listening ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 22.dp)) {
                    Text(text, style = HexTheme.type.body, color = colors.text3)
                    Caret(Modifier.padding(start = 3.dp))
                }
            HexGoalBoxState.Transcribing ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(min = 22.dp),
                ) {
                    HexSpinner(size = HexTheme.size.iconSmall)
                    Text(text, style = HexTheme.type.body, color = colors.text3)
                }
        }
        if (reason != null && (state == HexGoalBoxState.Set)) Text(reason, style = HexTheme.type.caption, color = colors.text2)
    }
}

/** The red caret of a take that's listening (`.live-ans::after`): 2 × 15 pt, pulsing. */
@Composable
private fun Caret(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "goal-caret")
    val alpha by transition.animateFloat(
        1f,
        0.35f,
        infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "goal-caret-alpha",
    )
    Box(
        modifier
            .width(2.dp)
            .height(15.dp)
            .alpha(alpha)
            .background(HexTheme.colors.red),
    )
}
