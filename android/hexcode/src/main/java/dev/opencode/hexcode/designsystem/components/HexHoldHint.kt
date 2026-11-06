package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * The line over the talk button while it's held (app-plan.md, Dictation › Hold hint), since the finger covers the
 * button: `‹ Drop` and `Lock ›`, very quiet. As the drag [lean]s towards a side (−1 drop to 1 lock, the button's
 * smoothed drag) that side brightens, drop to `red` and lock to `text1`, and the other fades. Once [armed], it reads
 * "Release to drop" or "Release to lock".
 */
@Composable
fun HexHoldHint(
    lean: Float,
    armed: HexTalkRelease?,
    dropLabel: String,
    lockLabel: String,
    releaseDropLabel: String,
    releaseLockLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val quiet = colors.text3.copy(alpha = colors.text3.alpha * 0.7f)
    val toLock = lean.coerceIn(0f, 1f)
    val toDrop = (-lean).coerceIn(0f, 1f)
    // The screen reader has the button's own label; this line is for the eye.
    Crossfade(armed, modifier.semantics { hideFromAccessibility() }, HexTheme.motion.fade(), label = "hold-hint") { side ->
        when (side) {
            HexTalkRelease.Drop -> Side(HexIcons.chevL, releaseDropLabel, colors.red, iconFirst = true)
            HexTalkRelease.Lock -> Side(HexIcons.chevR, releaseLockLabel, colors.text1, iconFirst = false)
            else ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val drop = lerp(quiet, colors.red, toDrop)
                    val lock = lerp(quiet, colors.text1, toLock)
                    Side(HexIcons.chevL, dropLabel, drop.copy(alpha = drop.alpha * (1f - toLock)), iconFirst = true)
                    Spacer(Modifier.width(28.dp))
                    Side(HexIcons.chevR, lockLabel, lock.copy(alpha = lock.alpha * (1f - toDrop)), iconFirst = false)
                }
        }
    }
}

@Composable
private fun Side(
    icon: ImageVector,
    label: String,
    color: Color,
    iconFirst: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (iconFirst) Icon(icon, null, tint = color, modifier = Modifier.size(HexTheme.size.iconSmall))
        Text(label, style = HexTheme.type.caption, color = color, maxLines = 1)
        if (!iconFirst) Icon(icon, null, tint = color, modifier = Modifier.size(HexTheme.size.iconSmall))
    }
}
