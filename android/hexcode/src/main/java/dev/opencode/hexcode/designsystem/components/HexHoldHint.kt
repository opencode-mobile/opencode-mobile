package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.Crossfade
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * The one quiet label over the talk button while it's held (app-plan.md, Dictation › Hold hint), since the finger
 * covers the button: "Slide to lock", in `text3` at 70 %. As the drag [lean]s right (towards 1) it brightens towards
 * `text1`; leaning left (towards −1) it fades out. Once [armed] it reads "Release to lock" (`text1`) or "Release to
 * drop" (`red`), crossfading over 0.2 s.
 */
@Composable
fun HexHoldHint(
    lean: Float,
    armed: HexTalkRelease?,
    slideLabel: String,
    releaseLockLabel: String,
    releaseDropLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val quiet = colors.text3.copy(alpha = colors.text3.alpha * 0.7f)
    // The screen reader has the button's own label and actions; this line is for the eye.
    Crossfade(armed, modifier.semantics { hideFromAccessibility() }, HexTheme.motion.fade(), label = "hold-hint") { side ->
        val style = HexTheme.type.caption
        when (side) {
            HexTalkRelease.Lock -> Text(releaseLockLabel, style = style, color = colors.text1, maxLines = 1)
            HexTalkRelease.Drop -> Text(releaseDropLabel, style = style, color = colors.red, maxLines = 1)
            else -> {
                val towardsLock = lerp(quiet, colors.text1, lean.coerceIn(0f, 1f))
                val faded = towardsLock.copy(alpha = towardsLock.alpha * (1f - (-lean).coerceIn(0f, 1f)))
                Text(slideLabel, style = style, color = faded, maxLines = 1)
            }
        }
    }
}
