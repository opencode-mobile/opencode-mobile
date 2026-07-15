package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor
import kotlin.math.roundToInt

enum class UnifiedDiffRowKind { Hunk, Metadata, Context, Addition, Deletion }

enum class UnifiedDiffContextMode { Collapsed, Expanded }

data class UnifiedDiffRow(
    val id: Int,
    val kind: UnifiedDiffRowKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

data class UnifiedDiffHunk(
    val id: Int,
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val rows: List<UnifiedDiffRow>,
)

data class UnifiedDiffDocument(
    val rows: List<UnifiedDiffRow>,
    val hunks: List<UnifiedDiffHunk>,
) {
    fun visibleRows(
        contextMode: UnifiedDiffContextMode,
        contextLines: Int = 3,
    ): List<UnifiedDiffRow> {
        if (contextMode == UnifiedDiffContextMode.Expanded) return rows
        val changedIndices =
            rows.indices.filter { index ->
                rows[index].kind == UnifiedDiffRowKind.Addition || rows[index].kind == UnifiedDiffRowKind.Deletion
            }
        return rows.filterIndexed { index, row ->
            row.kind != UnifiedDiffRowKind.Context || changedIndices.any { changed -> kotlin.math.abs(index - changed) <= contextLines }
        }
    }
}

object UnifiedDiffParser {
    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$")

    fun parse(patch: String): UnifiedDiffDocument {
        if (patch.isEmpty()) return UnifiedDiffDocument(emptyList(), emptyList())
        var oldLine: Int? = null
        var newLine: Int? = null
        var insideHunk = false
        var malformed = false
        val headers = mutableListOf<HunkHeader>()
        val lines =
            patch.split("\n").let { split ->
                if (patch.endsWith("\n") && split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
            }
        val rows =
            lines.mapIndexed { index, line ->
                val header = hunkHeader.matchEntire(line)
                when {
                    header != null -> {
                        val values = header.groupValues
                        val oldStart = values[1].toIntOrNull()
                        val oldCount = values[2].ifEmpty { "1" }.toIntOrNull()
                        val newStart = values[3].toIntOrNull()
                        val newCount = values[4].ifEmpty { "1" }.toIntOrNull()
                        if (oldStart == null || oldCount == null || newStart == null || newCount == null) {
                            malformed = true
                            insideHunk = false
                            UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, line)
                        } else {
                            oldLine = oldStart
                            newLine = newStart
                            insideHunk = true
                            headers += HunkHeader(index, line, oldStart, oldCount, newStart, newCount)
                            UnifiedDiffRow(index, UnifiedDiffRowKind.Hunk, null, null, line)
                        }
                    }
                    insideHunk && line.startsWith("+") -> {
                        headers.last().actualNewCount += 1
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Addition, null, newLine, line.drop(1))
                            .also { newLine = newLine?.plus(1) }
                    }
                    insideHunk && line.startsWith("-") -> {
                        headers.last().actualOldCount += 1
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Deletion, oldLine, null, line.drop(1))
                            .also { oldLine = oldLine?.plus(1) }
                    }
                    line.startsWith("\\ No newline at end of file") ->
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, line)
                    insideHunk && line.startsWith(" ") -> {
                        headers.last().actualOldCount += 1
                        headers.last().actualNewCount += 1
                        UnifiedDiffRow(
                            index,
                            UnifiedDiffRowKind.Context,
                            oldLine,
                            newLine,
                            line.removePrefix(" "),
                        ).also {
                            oldLine = oldLine?.plus(1)
                            newLine = newLine?.plus(1)
                        }
                    }
                    insideHunk -> {
                        malformed = true
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, line)
                    }
                    else -> {
                        if (line.startsWith("@@")) malformed = true
                        UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, line)
                    }
                }
            }
        if (malformed || headers.any { !it.isValid }) return UnifiedDiffDocument(rows, emptyList())
        val hunks =
            headers.mapIndexed { index, header ->
                val end = headers.getOrNull(index + 1)?.rowIndex ?: rows.size
                UnifiedDiffHunk(
                    id = header.rowIndex,
                    header = header.text,
                    oldStart = header.oldStart,
                    oldCount = header.oldCount,
                    newStart = header.newStart,
                    newCount = header.newCount,
                    rows = rows.subList(header.rowIndex + 1, end),
                )
            }
        return UnifiedDiffDocument(rows, hunks)
    }

    private data class HunkHeader(
        val rowIndex: Int,
        val text: String,
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        var actualOldCount: Int = 0,
        var actualNewCount: Int = 0,
    ) {
        val isValid: Boolean get() = actualOldCount == oldCount && actualNewCount == newCount
    }
}

@Composable
fun OpenCodeUnifiedDiff(
    file: FileDiff,
    modifier: Modifier = Modifier,
    contextMode: UnifiedDiffContextMode = UnifiedDiffContextMode.Collapsed,
    selectedHunkId: Int? = null,
    onSelectedHunkOffset: (Int) -> Unit = {},
    document: UnifiedDiffDocument,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .semantics { contentDescription = file.summary() },
        ) {
            document.visibleRows(contextMode).forEach { row ->
                val selected = row.id == selectedHunkId && row.kind == UnifiedDiffRowKind.Hunk
                val bringIntoViewRequester = remember(row.id) { BringIntoViewRequester() }
                LaunchedEffect(selected) {
                    if (selected) bringIntoViewRequester.bringIntoView()
                }
                UnifiedDiffRowView(
                    row,
                    selected = selected,
                    modifier =
                        Modifier
                            .bringIntoViewRequester(bringIntoViewRequester)
                            .onGloballyPositioned { coordinates ->
                                if (selected) onSelectedHunkOffset(coordinates.positionInParent().y.roundToInt())
                            },
                )
            }
        }
    }
}

@Composable
private fun UnifiedDiffRowView(
    row: UnifiedDiffRow,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val background =
        when (row.kind) {
            UnifiedDiffRowKind.Addition -> openCodeColor("diff-add").copy(alpha = 0.18f)
            UnifiedDiffRowKind.Deletion -> openCodeColor("diff-delete").copy(alpha = 0.16f)
            UnifiedDiffRowKind.Hunk ->
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            UnifiedDiffRowKind.Metadata, UnifiedDiffRowKind.Context -> Color.Transparent
        }
    val foreground =
        when (row.kind) {
            UnifiedDiffRowKind.Addition -> openCodeColor("diff-add")
            UnifiedDiffRowKind.Deletion -> openCodeColor("diff-delete")
            UnifiedDiffRowKind.Hunk, UnifiedDiffRowKind.Metadata -> openCodeColor("syntax-diff-unknown")
            UnifiedDiffRowKind.Context -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        modifier =
            modifier
                .background(background)
                .heightIn(min = 20.dp)
                .clearAndSetSemantics { contentDescription = row.accessibilityLabel() },
    ) {
        Text(row.oldLine?.toString().orEmpty(), Modifier.width(24.dp), style = CodeTextStyle)
        Text(row.newLine?.toString().orEmpty(), Modifier.width(24.dp), style = CodeTextStyle)
        Text(row.kind.marker(), Modifier.width(20.dp), color = foreground, style = CodeTextStyle)
        Text(row.text, Modifier.padding(horizontal = 8.dp), color = foreground, style = CodeTextStyle)
    }
}

private fun FileDiff.summary() = "${status.label()} file $path, $additions additions, $deletions deletions"

private fun FileDiffStatus.label() = name.lowercase().replaceFirstChar(Char::uppercase)

private fun UnifiedDiffRowKind.marker() =
    when (this) {
        UnifiedDiffRowKind.Addition -> "+"
        UnifiedDiffRowKind.Deletion -> "−"
        else -> ""
    }

private fun UnifiedDiffRow.accessibilityLabel() =
    listOfNotNull(kind.name.lowercase(), oldLine?.let { "old line $it" }, newLine?.let { "new line $it" }, text).joinToString(", ")
