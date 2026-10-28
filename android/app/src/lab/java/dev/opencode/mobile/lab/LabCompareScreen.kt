package dev.opencode.mobile.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeCodeFile
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

private val LabCompareWideWidth = 600.dp

@Composable
internal fun LabCompareScreen(
    state: LabCompareUiState,
    onNavigateBack: () -> Unit,
    onSelectDataSet: (String) -> Unit,
    onChangeDataSet: () -> Unit,
    onSelectLeftEngine: (String) -> Unit,
    onSelectRightEngine: (String) -> Unit,
) {
    val picking = state.phase == LabComparePhase.Picking
    BackHandler(enabled = state.backReturnsToPicker, onBack = onChangeDataSet)
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = if (picking) "Compare: pick a data set" else "Compare: ${state.title}",
            subtitle = state.subtitle.ifEmpty { null },
            leading = OpenCodeTopBarLeading.Back(if (state.backReturnsToPicker) onChangeDataSet else onNavigateBack),
            actions = if (picking) emptyList() else listOf(OpenCodeTopBarAction.Label("Data set", onClick = onChangeDataSet)),
            showDivider = true,
        )
        if (picking) {
            LabDataSetList(rows = state.pickerRows, onOpen = onSelectDataSet, modifier = Modifier.weight(1f).padding(top = 12.dp))
            return@Column
        }
        if (state.phase == LabComparePhase.Missing) {
            OpenCodeEmptyState(
                title = "Data set unavailable",
                message = "Pasted text and opened files last until the app restarts.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabChoiceRow(
                label = "Baseline",
                options = state.engines,
                selected = { it.id == state.leftEngineId },
                title = LabEngineOption::name,
                onSelect = { onSelectLeftEngine(it.id) },
            )
            LabChoiceRow(
                label = "Candidate",
                options = state.engines,
                selected = { it.id == state.rightEngineId },
                title = LabEngineOption::name,
                onSelect = { onSelectRightEngine(it.id) },
            )
            Text(
                text = state.summary?.let { "${it.agreementLabel} · ${it.differenceLabel}" } ?: "Highlighting both…",
                style = MaterialTheme.typography.titleSmall,
                color = openCodeColor("text-strong"),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val left = state.left
        val right = state.right
        if (state.phase == LabComparePhase.Comparing || left == null || right == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                OpenCodeStatusStack(title = "Highlighting", style = OpenCodeStatusStackStyle.Spinner)
            }
            return@Column
        }
        val leftList = rememberLazyListState()
        val rightList = rememberLazyListState()
        LabSyncedScroll(leftList, rightList)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= LabCompareWideWidth) {
                Row(Modifier.fillMaxSize()) {
                    LabComparePaneView(left, leftList, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(openCodeColor("border-weaker-base")))
                    LabComparePaneView(right, rightList, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    LabComparePaneView(left, leftList, Modifier.weight(1f).fillMaxWidth())
                    Box(Modifier.height(1.dp).fillMaxWidth().background(openCodeColor("border-weaker-base")))
                    LabComparePaneView(right, rightList, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LabComparePaneView(
    pane: LabComparePane,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(
            text = "${pane.engineName} · ${pane.timing.timeLabel} · ${pane.timing.spansLabel} · ${pane.timing.outcomeLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = openCodeColor("text-weak"),
            maxLines = 1,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(openCodeColor("surface-raised-base"))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        OpenCodeCodeFile(lines = pane.lines, state = listState, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
