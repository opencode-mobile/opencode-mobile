package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.Crossfade
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * The one quiet label over the talk button while it's held (app-plan.md, Dictation › Hold hint), since the finger
 * covers the button: "Slide to lock", in `text3` at 70 %. It is only an instruction, so it stays subtle all the way and
 * the button's own animation is what you notice: leaning right ([lean] towards 1) it keeps its colour, leaning left
 * (towards −1) it fades out. Once [armed] it reads "Release to lock" in the same quiet colour, or "Release to drop" in
 * `red` at 55 %, crossfading over 0.2 s.
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
            HexTalkRelease.Lock -> Text(releaseLockLabel, style = style, color = quiet, maxLines = 1)
            HexTalkRelease.Drop -> Text(releaseDropLabel, style = style, color = colors.red.copy(alpha = 0.55f), maxLines = 1)
            else -> {
                val fading = quiet.copy(alpha = quiet.alpha * (1f - (-lean).coerceIn(0f, 1f)))
                Text(slideLabel, style = style, color = fading, maxLines = 1)
            }
        }
    }
}
