package dev.opencode.mobile.lab

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeKeyValueRow
import dev.opencode.mobile.ui.components.OpenCodeKeyValueStyle

/** A labelled row of choice badges that scrolls sideways when it does not fit. */
@Composable
internal fun <T> LabChoiceRow(
    label: String,
    options: List<T>,
    selected: (T) -> Boolean,
    title: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Per option, on top of [enabled]. */
    isEnabled: (T) -> Boolean = { true },
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = openCodeColor("text-weak"),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                OpenCodeChoiceBadge(
                    label = title(option),
                    selected = selected(option),
                    enabled = enabled && isEnabled(option),
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** Time to spans, chars/ms, outcome, and span count of one engine call. */
@Composable
internal fun LabTimingStrip(
    timing: LabTiming?,
    modifier: Modifier = Modifier,
    pending: String = "Highlighting…",
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (timing == null) {
            OpenCodeKeyValueRow(label = "Time to spans", value = pending, style = OpenCodeKeyValueStyle.Stacked)
        } else {
            OpenCodeKeyValueRow(
                label = "Time to spans",
                value = timing.timeLabel,
                style = OpenCodeKeyValueStyle.Stacked,
                modifier = Modifier.weight(1.2f),
            )
            OpenCodeKeyValueRow(
                label = "Chars/ms",
                value = timing.rateLabel,
                style = OpenCodeKeyValueStyle.Stacked,
                modifier = Modifier.weight(1f),
            )
            OpenCodeKeyValueRow(
                label = "Outcome",
                value = timing.outcomeLabel,
                style = OpenCodeKeyValueStyle.Stacked,
                modifier = Modifier.weight(1f),
            )
            OpenCodeKeyValueRow(
                label = "Spans",
                value = LabFormat.count(timing.spanCount),
                style = OpenCodeKeyValueStyle.Stacked,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A report table: a bold header row, then cells in a monospaced grid that scrolls sideways.
 * Each column is as wide as its longest cell, up to [LAB_TABLE_MAX_COLUMN_CHARS] characters.
 */
@Composable
internal fun LabTable(
    headers: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val charWidth =
        remember(textMeasurer, density) {
            with(density) {
                textMeasurer
                    .measure("0", CodeTextStyle)
                    .size.width
                    .toDp()
            }
        }
    val widths =
        remember(headers, rows, charWidth) {
            headers.indices.map { column ->
                val longest = (listOf(headers) + rows).maxOf { it.getOrNull(column)?.length ?: 0 }
                charWidth * (longest.coerceAtMost(LAB_TABLE_MAX_COLUMN_CHARS) + 1) + LabTableCellPadding * 2
            }
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        LabTableRow(headers, widths, header = true)
        rows.forEachIndexed { index, row ->
            LabTableRow(row, widths, header = false, striped = index % 2 == 1)
        }
    }
}

private const val LAB_TABLE_MAX_COLUMN_CHARS = 32
private val LabTableCellPadding = 6.dp

@Composable
private fun LabTableRow(
    cells: List<String>,
    widths: List<Dp>,
    header: Boolean,
    striped: Boolean = false,
) {
    Row(
        modifier = if (striped) Modifier.background(openCodeColor("surface-raised-base")) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, cell ->
            Text(
                text = cell,
                style = CodeTextStyle,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = openCodeColor(if (header) "text-strong" else "text-base"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .width(widths.getOrElse(index) { widths.last() })
                        .padding(horizontal = LabTableCellPadding, vertical = 4.dp),
            )
        }
    }
}

/** Hands a report to the share sheet as text. */
internal fun shareLabReport(
    context: Context,
    subject: String,
    text: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(Intent.createChooser(send, subject))
}
