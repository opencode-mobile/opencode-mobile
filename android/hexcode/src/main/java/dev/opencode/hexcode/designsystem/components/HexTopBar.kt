package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * A page's top bar (`.pk-top`): back, then a two-line title, the page name over the computer it's looking at (with
 * a [subtitleDot]), and optional trailing [actions].
 */
@Composable
fun HexTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    backDescription: String,
    modifier: Modifier = Modifier,
    subtitleDot: HexDotTone = HexDotTone.Green,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = HexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(56.dp)
                .padding(horizontal = HexTheme.space.screen),
    ) {
        HexRoundButton(HexIcons.chevL, backDescription, onBack)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = HexTheme.type.headline,
                color = colors.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    HexDot(subtitleDot)
                    Text(subtitle, style = HexTheme.type.caption, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        actions()
    }
}

/** The agent's state in home's top bar. */
enum class HexAgentState { Idle, Working, NeedsInput, Reconnecting }

/**
 * Home's top bar (`.tb`): the state slot (an icon, the [label] and an optional mono [timer]) on the left, round
 * buttons ([actions]) on the right.
 */
@Composable
fun HexTopBar(
    state: HexAgentState,
    label: String,
    modifier: Modifier = Modifier,
    timer: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = HexTheme.colors
    val headColor =
        when (state) {
            HexAgentState.Idle -> colors.text2
            HexAgentState.Working -> colors.text1
            HexAgentState.NeedsInput, HexAgentState.Reconnecting -> colors.amber
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .height(HexTheme.size.touch),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { StateIcon(state) }
            Text(label, style = HexTheme.type.headline, color = headColor, maxLines = 1)
            if (timer != null) Text(timer, style = HexTheme.type.mono, color = colors.text3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
private fun StateIcon(state: HexAgentState) {
    val colors = HexTheme.colors
    when (state) {
        HexAgentState.Idle -> {
            val ring = colors.text3
            Canvas(Modifier.size(16.dp)) {
                drawCircle(ring, radius = size.minDimension * 5.5f / 16f, style = Stroke(1.5.dp.toPx()))
            }
        }
        HexAgentState.NeedsInput ->
            Box(
                Modifier
                    .size(16.dp)
                    .background(colors.amber.copy(alpha = 0.16f), CircleShape)
                    .padding(4.dp)
                    .background(colors.amber, CircleShape),
            )
        HexAgentState.Working, HexAgentState.Reconnecting -> {
            val transition = rememberInfiniteTransition(label = "state")
            val turn by transition.animateFloat(
                0f,
                360f,
                infiniteRepeatable(tween(if (state == HexAgentState.Working) 1600 else 900, easing = LinearEasing), RepeatMode.Restart),
                label = "state-turn",
            )
            Icon(
                HexIcons.spin,
                null,
                tint = if (state == HexAgentState.Working) colors.green else colors.amber,
                modifier = Modifier.size(16.dp).rotate(turn),
            )
        }
    }
}
