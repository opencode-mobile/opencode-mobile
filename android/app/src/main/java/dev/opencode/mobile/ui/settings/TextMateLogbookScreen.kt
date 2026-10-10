package dev.opencode.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.textmate.TextMateLogbookEntry
import dev.opencode.mobile.markdown.TextMateHighlightSpeed
import dev.opencode.mobile.markdown.TextMateHighlightSpeedBand
import dev.opencode.mobile.markdown.TextMateMetricStage
import dev.opencode.mobile.markdown.TextMateParseObservation
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.TextMateStageSummary
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeKeyValueRow
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TextMateLogbookScreen(
    state: TextMateLogbookUiState,
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEntryID by remember { mutableStateOf<String?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "TextMate Logbook",
            leading = openCodeDetailLeading(detailControl, onNavigateBack),
            showDivider = true,
        )

        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.CompactText("Refresh"),
                        contrast = OpenCodeButtonContrast.Low,
                        onClick = onRefresh,
                    )
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.CompactText("Clear"),
                        contrast = OpenCodeButtonContrast.Low,
                        enabled = state.entries.isNotEmpty(),
                        onClick = { showClearConfirmation = true },
                    )
                }

                when {
                    state.loading ->
                        OpenCodeStatusStack(
                            title = "Loading TextMate logbook",
                            style = OpenCodeStatusStackStyle.Spinner,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    state.entries.isEmpty() ->
                        Text(
                            text = "No TextMate parsing entries yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = openCodeColor("text-base"),
                            modifier = Modifier.padding(24.dp),
                        )
                    else ->
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding =
                                androidx.compose.foundation.layout.PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 24.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.droppedEntries > 0 || state.storageError != null) {
                                item(key = "store-health") {
                                    TextMateStoreHealth(
                                        droppedEntries = state.droppedEntries,
                                        storageError = state.storageError,
                                    )
                                }
                            }
                            items(state.entries, key = TextMateLogbookEntry::id) { entry ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OpenCodeListItem(
                                        title = entry.observation.displayLanguage(),
                                        subtitle = entry.observation.summary(),
                                        caption = entry.timestampLabel(),
                                        status = entry.observation.status(),
                                        selected = selectedEntryID == entry.id,
                                        style = OpenCodeListItemStyle.Card,
                                        onClick = {
                                            selectedEntryID =
                                                if (selectedEntryID == entry.id) null else entry.id
                                        },
                                    )
                                    if (selectedEntryID == entry.id) {
                                        TextMateLogbookDetails(entry.observation)
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

    OpenCodeDialog(
        visible = showClearConfirmation,
        title = "Clear TextMate logbook?",
        subtitle = "This removes persisted parsing measurements.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Clear",
                onClick = {
                    showClearConfirmation = false
                    selectedEntryID = null
                    onClear()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = { showClearConfirmation = false },
            ),
        onDismissRequest = { showClearConfirmation = false },
    )
}

@Composable
private fun TextMateStoreHealth(
    droppedEntries: Long,
    storageError: String?,
) {
    val message =
        buildString {
            if (droppedEntries > 0) append("$droppedEntries entries dropped under writer pressure.")
            if (droppedEntries > 0 && storageError != null) append(' ')
            storageError?.let { append("Storage error: $it.") }
        }
    OpenCodeInlineNotice(
        message = message,
        tone = OpenCodeInlineNoticeTone.Error,
        variant = OpenCodeInlineNoticeVariant.Banner,
    )
}

@Composable
private fun TextMateLogbookDetails(observation: TextMateParseObservation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = openCodeColor("surface-raised-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DetailLine("Fence", "${observation.fenceIndex}/${observation.fenceCount}")
            DetailLine("Outcome", observation.outcome.name)
            DetailLine("Backend", observation.backend ?: "none")
            DetailLine("Fence lines", observation.fenceLines.toString())
            DetailLine(
                "Throughput",
                TextMateHighlightSpeed.throughputLabel(
                    totalNanoseconds = observation.totalNanoseconds,
                    fenceCharacters = observation.fenceCharacters,
                    spanCount = observation.spanCount,
                ) ?: "n/a",
            )
            DetailLine("Diagnostics", observation.diagnosticKinds.joinToString().ifEmpty { "none" })
            Text(
                text = "Stage timings overlap and are not additive.",
                style = MaterialTheme.typography.labelSmall,
                color = openCodeColor("text-weaker"),
            )
            observation.stages.forEach { stage ->
                DetailLine(
                    stage.stage.displayName(),
                    "${stage.totalNanoseconds.milliseconds()} ms · " +
                        "${stage.eventCount} events · max ${stage.maximumNanoseconds.milliseconds()} ms",
                )
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
) {
    OpenCodeKeyValueRow(label = label, value = value)
}

private fun TextMateParseObservation.displayLanguage(): String = canonicalLanguage ?: requestedLanguage ?: "Unspecified language"

private fun TextMateParseObservation.summary(): String {
    val base =
        "$messageCharacters message chars · $fenceCharacters fence chars · $spanCount spans · " +
            "${totalNanoseconds.milliseconds()} ms"
    val throughput =
        TextMateHighlightSpeed.throughputLabel(
            totalNanoseconds = totalNanoseconds,
            fenceCharacters = fenceCharacters,
            spanCount = spanCount,
        )
    return if (throughput == null) base else "$base · $throughput"
}

private fun TextMateParseObservation.status(): OpenCodeListItemStatus {
    val speed =
        TextMateHighlightSpeed.bandForOutcome(
            outcome = outcome,
            totalNanoseconds = totalNanoseconds,
            fenceCharacters = fenceCharacters,
        )
    if (speed != null) {
        return when (speed) {
            TextMateHighlightSpeedBand.Fast ->
                OpenCodeListItemStatus("fast", OpenCodeListItemStatusTone.Success)
            TextMateHighlightSpeedBand.Ok ->
                OpenCodeListItemStatus("ok", OpenCodeListItemStatusTone.Warning)
            TextMateHighlightSpeedBand.Slow ->
                OpenCodeListItemStatus("slow", OpenCodeListItemStatusTone.Error)
        }
    }
    return when (outcome) {
        TextMateParseOutcome.Highlighted ->
            OpenCodeListItemStatus("success", OpenCodeListItemStatusTone.Success)
        TextMateParseOutcome.NoSpans,
        TextMateParseOutcome.Unsupported,
        TextMateParseOutcome.Fallback,
        -> OpenCodeListItemStatus(outcome.name.lowercase(), OpenCodeListItemStatusTone.Warning)
        TextMateParseOutcome.Failed,
        TextMateParseOutcome.Cancelled,
        TextMateParseOutcome.BudgetExceeded,
        -> OpenCodeListItemStatus(outcome.name.lowercase(), OpenCodeListItemStatusTone.Error)
    }
}

private fun TextMateLogbookEntry.timestampLabel(): String =
    DateFormat
        .getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(timestampEpochMilliseconds))

private fun Long.milliseconds(): String = String.format(Locale.US, "%.3f", toDouble() / 1_000_000.0)

private fun TextMateMetricStage.displayName(): String =
    when (this) {
        TextMateMetricStage.GrammarLoad -> "Grammar load"
        TextMateMetricStage.GrammarLookup -> "Grammar lookup"
        TextMateMetricStage.RuleResolve -> "Rule resolve"
        TextMateMetricStage.RegexCompile -> "Regex compile"
        TextMateMetricStage.RegexSearch -> "Regex search"
        TextMateMetricStage.NativeRegexSearch -> "Native regex search"
        TextMateMetricStage.PreparedLineBuffer -> "Prepared line buffer"
        TextMateMetricStage.Utf8IndexMap -> "UTF-8 index map"
        TextMateMetricStage.Tokenize -> "Tokenize"
        TextMateMetricStage.Repair -> "Repair"
        TextMateMetricStage.IncrementalReuse -> "Incremental reuse"
    }

@Preview(showBackground = true)
@Composable
private fun TextMateLogbookScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        TextMateLogbookScreen(
            state =
                TextMateLogbookUiState(
                    loading = false,
                    entries =
                        listOf(
                            TextMateLogbookEntry(
                                id = "preview",
                                timestampEpochMilliseconds = 0,
                                observation =
                                    TextMateParseObservation(
                                        outcome = TextMateParseOutcome.Highlighted,
                                        messageCharacters = 400,
                                        fenceCharacters = 120,
                                        fenceLines = 8,
                                        fenceIndex = 1,
                                        fenceCount = 1,
                                        spanCount = 18,
                                        backend = "joni",
                                        totalNanoseconds = 2_500_000,
                                        stages =
                                            listOf(
                                                TextMateStageSummary(
                                                    stage = TextMateMetricStage.Tokenize,
                                                    eventCount = 1,
                                                    totalNanoseconds = 2_000_000,
                                                    maximumNanoseconds = 2_000_000,
                                                    successCount = 1,
                                                    failureCount = 0,
                                                    cacheHitCount = 0,
                                                    cacheMissCount = 0,
                                                ),
                                            ),
                                        diagnosticKinds = emptyList(),
                                    ),
                            ),
                        ),
                ),
            onNavigateBack = {},
            onRefresh = {},
            onClear = {},
        )
    }
}
