package dev.opencode.mobile.ui.playground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.openCodeBottomSheetScrollTarget
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * The scenario picker the playground's chat title opens: scenarios grouped as the issue lists
 * them, the open one selected, and previous / next in the header to step through them in place.
 */
@Composable
internal fun PlaygroundScenarioSheet(
    visible: Boolean,
    current: PlaygroundScenarioId,
    stepCounts: Map<PlaygroundScenarioId, Int>,
    onSelect: (PlaygroundScenarioId) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    OpenCodeBottomSheet(
        visible = visible,
        title = "Scenarios",
        onDismissRequest = onDismissRequest,
        height = OpenCodeBottomSheetHeight.Half,
        scrollTargetKey = current,
        titleContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ChevronLeft, "Previous scenario"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onPrevious,
                    modifier = Modifier.testTag("playgroundPreviousScenario"),
                )
                Text(
                    text = current.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = openCodeColor("text-strong"),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ChevronRight, "Next scenario"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onNext,
                    modifier = Modifier.testTag("playgroundNextScenario"),
                )
            }
        },
    ) {
        PlaygroundScenarioGroup.entries.forEach { group ->
            OpenCodeSectionHeader(title = group.title)
            PlaygroundScenarioId.entries.filter { it.group == group }.forEach { id ->
                val steps = stepCounts[id] ?: 0
                OpenCodeListItem(
                    title = id.label,
                    subtitle = id.summary,
                    caption = if (steps > 0) "$steps steps" else null,
                    selected = id == current,
                    onClick = { onSelect(id) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Only the open row carries the mark, so a newly opened one attaches it and the sheet centres it.
                            .then(if (id == current) Modifier.openCodeBottomSheetScrollTarget(id) else Modifier)
                            .testTag("playgroundScenario.${id.name}"),
                )
            }
        }
    }
}
