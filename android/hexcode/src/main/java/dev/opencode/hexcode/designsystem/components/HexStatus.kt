package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.FullSpeed
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme
import kotlinx.coroutines.withContext

/** The spinner (`I.spin`): the `spin` ring turning once every 0.9 s. */
@Composable
fun HexSpinner(
    modifier: Modifier = Modifier,
    size: Dp = HexTheme.size.icon,
    tint: Color = HexTheme.colors.solid,
) {
    val turn = rememberTurn(900)
    Icon(HexIcons.spin, null, tint = tint, modifier = modifier.size(size).rotate(turn))
}

/**
 * An angle turning once every [periodMs], at full speed whatever the animator scale: spinners mean "working", so they
 * keep turning with Reduce Motion (app-plan.md, Motion and Reduce Motion › What stays).
 */
@Composable
fun rememberTurn(periodMs: Int): Float {
    val turn = remember { Animatable(0f) }
    LaunchedEffect(periodMs) {
        withContext(FullSpeed) {
            turn.snapTo(0f)
            turn.animateTo(360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart))
        }
    }
    return turn.value
}

/** What a [HexStatusLine] reports. */
enum class HexStatusTone {
    /** Green with a check. */
    Ok,

    /** Amber with the alert icon. */
    Warning,

    /** `text3` with a spinner. */
    Waiting,

    /** `text3`, no icon: a hint before anything has happened. */
    Hint,
}

/** One status line (`.mt-st`, `.fb-note`): an icon and text that wraps under the line, not under the icon. */
@Composable
fun HexStatusLine(
    text: String,
    tone: HexStatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val color =
        when (tone) {
            HexStatusTone.Ok -> colors.green
            HexStatusTone.Warning -> colors.amber
            HexStatusTone.Waiting, HexStatusTone.Hint -> colors.text3
        }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        val icon = Modifier.padding(top = 1.dp).size(14.dp)
        when (tone) {
            HexStatusTone.Ok -> Icon(HexIcons.check, null, tint = color, modifier = icon)
            HexStatusTone.Warning -> Icon(HexIcons.alert, null, tint = color, modifier = icon)
            HexStatusTone.Waiting -> Box(icon) { HexSpinner(size = 14.dp) }
            HexStatusTone.Hint -> Unit
        }
        Text(text, style = HexTheme.type.caption, color = color, modifier = Modifier.weight(1f, fill = false))
    }
}
