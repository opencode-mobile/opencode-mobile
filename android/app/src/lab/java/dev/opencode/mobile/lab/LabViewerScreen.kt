package dev.opencode.mobile.lab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeCodeFile
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
internal fun LabViewerScreen(
    state: LabViewerUiState,
    onNavigateBack: () -> Unit,
    onSelectEngine: (String) -> Unit,
    onCompare: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = state.title,
            subtitle = state.subtitle.ifEmpty { null },
            leading = OpenCodeTopBarLeading.Back(onNavigateBack),
            actions =
                if (state.phase == LabViewerPhase.Missing || state.engines.size < 2) {
                    emptyList()
                } else {
                    listOf(OpenCodeTopBarAction.Label("Compare", onClick = onCompare))
                },
            showDivider = true,
        )
        if (state.phase == LabViewerPhase.Missing) {
            OpenCodeEmptyState(
                title = "Data set unavailable",
                message = "Pasted text and opened files last until the app restarts.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        LabChoiceRow(
            label = "Engine",
            options = state.engines,
            selected = { it.id == state.engineId },
            title = LabEngineOption::name,
            onSelect = { onSelectEngine(it.id) },
            modifier = Modifier.padding(top = 12.dp),
        )
        LabTimingStrip(
            timing = state.timing,
            pending = if (state.phase == LabViewerPhase.Loading) "Generating…" else "Highlighting…",
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.phase == LabViewerPhase.Loading) {
                OpenCodeStatusStack(
                    title = "Generating text",
                    style = OpenCodeStatusStackStyle.Spinner,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                OpenCodeCodeFile(lines = state.lines, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
