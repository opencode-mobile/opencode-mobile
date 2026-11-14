package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
 * The agent's to-do list (`.fx-prog`): one 18 × 3 segment per item ([steps]), then [label] ("3 of 4 · <the item>") in
 * `caption`, `text2`, and "›". Nothing is cut: a label too long for the line beside the segments goes under them and
 * wraps. Tapping it calls [onClick].
 */
@OptIn(ExperimentalLayoutApi::class)
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
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
        // The item keeps its inline code boxes (HexCodeText); "›" ends the text, in text3.
        val text =
            remember(label, chevronColor) {
                buildAnnotatedString {
                    append(label)
                    append("\u00A0")
                    withStyle(SpanStyle(color = chevronColor)) { append("›") }
                }
            }
        HexCodeText(text, style = HexTheme.type.caption, color = labelColor)
    }
}
