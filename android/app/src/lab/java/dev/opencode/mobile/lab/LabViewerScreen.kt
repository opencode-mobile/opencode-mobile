package dev.opencode.mobile.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeCodeFile
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeKeyValueRow
import dev.opencode.mobile.ui.components.OpenCodeKeyValueStyle
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
internal fun LabViewerScreen(
    state: LabViewerUiState,
    onNavigateBack: () -> Unit,
    onRender: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = state.title,
            subtitle = state.subtitle.ifEmpty { null },
            leading = OpenCodeTopBarLeading.Back(onNavigateBack),
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
        // Buttons stay enabled while rendering: another engine replaces the run in progress.
        LabChoiceRow(
            label = "Render with",
            options = state.engines,
            selected = { it.id == state.selectedEngineId },
            title = LabViewerEngine::name,
            onSelect = { onRender(it.id) },
            enabled = state.phase != LabViewerPhase.Loading,
            isEnabled = LabViewerEngine::enabled,
            modifier = Modifier.padding(top = 12.dp),
        )
        state.engines.mapNotNull { it.disabledReason }.forEach { reason ->
            LabViewerNote(reason, Modifier.padding(top = 4.dp))
        }
        when (state.phase) {
            LabViewerPhase.Rendering -> LabRenderingStrip(state.engineName(state.renderingEngineId), onCancel)
            LabViewerPhase.Rendered -> LabTimingStrip(timing = state.timing)
            else ->
                OpenCodeKeyValueRow(
                    label = if (state.cancelled) "Cancelled" else "Not highlighted",
                    value = if (state.phase == LabViewerPhase.Loading) "Generating…" else "Plain text; tap an engine to render",
                    style = OpenCodeKeyValueStyle.Stacked,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
        }
        state.backgroundNote?.let { LabViewerNote(it, Modifier.padding(bottom = 6.dp)) }
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

/** Which engine is rendering, an indeterminate bar, and Cancel. */
@Composable
private fun LabRenderingStrip(
    engineName: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Rendering with $engineName…",
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-strong"),
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Cancel"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = onCancel,
        )
    }
}

@Composable
private fun LabViewerNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = openCodeColor("text-weak"),
        modifier = modifier.padding(horizontal = 16.dp),
    )
}
