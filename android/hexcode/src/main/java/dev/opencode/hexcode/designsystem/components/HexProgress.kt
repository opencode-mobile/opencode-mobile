package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/** One item's segment in a [HexProgress]. */
enum class HexProgressStep {
    /** Not started: faint (`text4`). */
    Pending,

    /** In progress: half lit. */
    Current,

    /** Completed: lit (`text1`). */
    Done,
}

/**
 * The agent's to-do list in one line (`.fx-prog`): one 18 × 3 segment per item ([steps]), then [label] ("3 of 4 ·
 * <the item>") in `caption`, `text2`, on one line, and "›". Tapping it calls [onClick].
 */
@Composable
fun HexProgress(
    steps: List<HexProgressStep>,
    label: AnnotatedString,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val labelColor by animateColorAsState(if (pressed) colors.text1 else colors.text2, HexTheme.motion.press(), label = "progress")
    val chevronColor by animateColorAsState(if (pressed) colors.text1 else colors.text3, HexTheme.motion.press(), label = "progress-chev")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .semantics(mergeDescendants = true) {}
                .clickable(source, null, role = Role.Button, onClick = onClick)
                .padding(vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEach { step ->
                val lit = colors.text1
                val faint = colors.text4
                Box(
                    Modifier
                        .size(width = 18.dp, height = 3.dp)
                        .clip(CircleShape)
                        .drawBehind {
                            drawRect(if (step == HexProgressStep.Done) lit else faint)
                            if (step == HexProgressStep.Current) drawRect(lit, size = Size(size.width / 2, size.height))
                        },
                )
            }
        }
        // Inline code in the item gets its box (HexCodeText).
        HexCodeText(
            label,
            style = HexTheme.type.caption,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("›", style = HexTheme.type.caption, color = chevronColor)
    }
}
