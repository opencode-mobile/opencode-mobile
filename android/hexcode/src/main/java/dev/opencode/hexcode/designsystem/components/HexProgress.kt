package dev.opencode.hexcode.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexTheme

/**
 * The plan's progress (`.fx-prog`): one 18 × 3 segment per step, lit (`text1`) when [steps] says it's done, faint
 * (`text4`) when not, then a mono [label] such as "2/4 · details ›". Tapping it calls [onClick].
 */
@Composable
fun HexProgress(
    steps: List<Boolean>,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HexTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val labelColor by animateColorAsState(if (pressed) colors.text1 else colors.text3, HexTheme.motion.press(), label = "progress")
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
            steps.forEach { done ->
                Box(Modifier.size(width = 18.dp, height = 3.dp).background(if (done) colors.text1 else colors.text4, CircleShape))
            }
        }
        Text(label, style = HexTheme.type.mono, color = labelColor, maxLines = 1)
    }
}
