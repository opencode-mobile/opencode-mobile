package dev.opencode.mobile.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.highlight.bench.HighlightBenchDataKind
import dev.opencode.mobile.highlight.bench.HighlightBenchSizeClass
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
internal fun LabDataSetsScreen(
    state: LabDataSetsUiState,
    onNavigateBack: () -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onSelectSizeClass: (HighlightBenchSizeClass?) -> Unit,
    onSelectKind: (HighlightBenchDataKind?) -> Unit,
    onOpen: (String) -> Unit,
    onPaste: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Data sets",
            subtitle = "${state.rows.size} shown",
            leading = OpenCodeTopBarLeading.Back(onNavigateBack),
            actions = listOf(OpenCodeTopBarAction.Label("Paste", onClick = onPaste)),
            showDivider = true,
        )
        OpenCodeReadableColumn(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LabChoiceRow(
                        label = "Language",
                        options = listOf<String?>(null) + state.languages,
                        selected = { it == state.language },
                        title = { it ?: "All" },
                        onSelect = onSelectLanguage,
                    )
                    LabChoiceRow(
                        label = "Size",
                        options = listOf<HighlightBenchSizeClass?>(null) + state.sizeClasses,
                        selected = { it == state.sizeClass },
                        title = { it?.label ?: "All" },
                        onSelect = onSelectSizeClass,
                    )
                    LabChoiceRow(
                        label = "Kind",
                        options = listOf<HighlightBenchDataKind?>(null) + state.kinds,
                        selected = { it == state.kind },
                        title = { it?.label ?: "All" },
                        onSelect = onSelectKind,
                    )
                }
                LabDataSetList(
                    rows = state.rows,
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Data set rows: name, then language, size, and characters, with the kind as caption. */
@Composable
internal fun LabDataSetList(
    rows: List<LabDataSetRow>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedIds: Collection<String> = emptyList(),
) {
    if (rows.isEmpty()) {
        OpenCodeEmptyState(
            title = "No data sets",
            message = "No data set matches these filters.",
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = LabDataSetRow::id) { row ->
            OpenCodeListItem(
                title = row.name,
                subtitle = row.subtitle,
                caption = row.kind,
                selected = row.id in selectedIds,
                style = OpenCodeListItemStyle.Card,
                onClick = { onOpen(row.id) },
            )
        }
    }
}
