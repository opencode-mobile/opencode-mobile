package dev.opencode.mobile.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderInset
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderVariant
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
internal fun LabBenchmarkScreen(
    state: LabBenchmarkUiState,
    onNavigateBack: () -> Unit,
    onSelectScope: (LabBenchmarkScope) -> Unit,
    onToggleDataSet: (String) -> Unit,
    onToggleEngine: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Benchmark",
            subtitle = state.progress?.takeIf { state.running }?.label,
            leading = OpenCodeTopBarLeading.Back(onNavigateBack),
            showDivider = true,
        )
        OpenCodeReadableColumn(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "scope") {
                    LabChoiceRow(
                        label = "Run",
                        options = LabBenchmarkScope.entries,
                        selected = { it == state.scope },
                        title = LabBenchmarkScope::label,
                        onSelect = onSelectScope,
                        enabled = !state.running,
                    )
                }
                item(key = "engines") {
                    LabChoiceRow(
                        label = "Engines, in order (the first is the baseline)",
                        options = state.engines,
                        selected = { it.id in state.engineOrder },
                        title = state::engineLabel,
                        onSelect = { onToggleEngine(it.id) },
                        enabled = !state.running,
                    )
                }
                item(key = "actions") {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.running) {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.CompactText(if (state.stopping) "Stopping…" else "Stop"),
                                contrast = OpenCodeButtonContrast.Destructive,
                                enabled = !state.stopping,
                                onClick = onStop,
                            )
                        } else {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.CompactText("Run"),
                                enabled = state.canRun,
                                onClick = onRun,
                            )
                        }
                    }
                }
                state.progress?.takeIf { state.running }?.let { progress ->
                    item(key = "progress") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(progress.label, style = MaterialTheme.typography.titleSmall, color = openCodeColor("text-strong"))
                            Text(progress.detail, style = MaterialTheme.typography.bodySmall, color = openCodeColor("text-weak"))
                            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                state.error?.let { error ->
                    item(key = "error") {
                        OpenCodeInlineNotice(
                            message = error,
                            tone = OpenCodeInlineNoticeTone.Error,
                            variant = OpenCodeInlineNoticeVariant.Banner,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                state.tables.forEach { table ->
                    item(key = "table-${table.title}") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OpenCodeSectionHeader(title = table.title, variant = OpenCodeSectionHeaderVariant.Plain)
                            LabTable(headers = table.headers, rows = table.rows)
                        }
                    }
                }
                if (state.comparisons.isNotEmpty()) {
                    item(key = "comparison") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OpenCodeSectionHeader(
                                title = "Compared with ${state.baselineName.orEmpty()} (candidate ÷ baseline)",
                                variant = OpenCodeSectionHeaderVariant.Plain,
                            )
                            LabTable(
                                headers = listOf("Case", "Section", "Candidate", "Baseline", "Candidate", "Ratio"),
                                rows =
                                    state.comparisons.map {
                                        listOf(
                                            it.caseTitle,
                                            it.section,
                                            it.candidate,
                                            it.baselineMs,
                                            it.candidateMs,
                                            it.ratio,
                                        )
                                    },
                            )
                        }
                    }
                }
                state.report?.takeIf { !state.running }?.let { report ->
                    item(key = "report") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpenCodeSectionHeader(
                                title =
                                    if (state.reportFromStorage) {
                                        "Last report, ${report.savedAtLabel}"
                                    } else {
                                        "Report saved ${report.savedAtLabel}"
                                    },
                                variant = OpenCodeSectionHeaderVariant.Plain,
                                inset = OpenCodeSectionHeaderInset.Flush,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OpenCodeButton(
                                    variant = OpenCodeButtonVariant.CompactText("Share Markdown"),
                                    contrast = OpenCodeButtonContrast.Low,
                                    onClick = { shareLabReport(context, "Highlighting Lab report", report.markdown) },
                                )
                                OpenCodeButton(
                                    variant = OpenCodeButtonVariant.CompactText("Share JSON"),
                                    contrast = OpenCodeButtonContrast.Low,
                                    onClick = { shareLabReport(context, "Highlighting Lab report (JSON)", report.json) },
                                )
                            }
                        }
                    }
                    state.storedReportDocument?.takeIf { state.reportFromStorage }?.let { document ->
                        item(key = "stored-report") {
                            OpenCodeRenderedMarkdown(
                                document = document,
                                variant = OpenCodeRenderedMarkdownVariant.Standalone,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
                if (state.scope == LabBenchmarkScope.Selected && !state.running) {
                    item(key = "datasets-header") {
                        OpenCodeSectionHeader(title = "Data sets (${state.selectedDataSetIds.size} picked)")
                    }
                    items(state.dataSets, key = { "dataset-${it.id}" }) { row ->
                        OpenCodeListItem(
                            title = row.name,
                            subtitle = row.subtitle,
                            caption = row.kind,
                            selected = row.id in state.selectedDataSetIds,
                            style = OpenCodeListItemStyle.Card,
                            onClick = { onToggleDataSet(row.id) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}
