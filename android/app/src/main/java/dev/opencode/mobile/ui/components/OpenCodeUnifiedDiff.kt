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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.openCodeColor

enum class UnifiedDiffRowKind { Hunk, Metadata, Context, Addition, Deletion }

enum class UnifiedDiffContextMode { Collapsed, Expanded }

data class UnifiedDiffRow(
    val index: Int,
    val kind: UnifiedDiffRowKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

object UnifiedDiffParser {
    fun parse(patch: String): List<UnifiedDiffRow> {
        var oldLine: Int? = null
        var newLine: Int? = null
        return patch.split("\n").mapIndexed { index, line ->
            when {
                line.startsWith("@@") -> {
                    val starts =
                        line.split(" ").mapNotNull { token ->
                            token
                                .takeIf { it.startsWith("-") || it.startsWith("+") }
                                ?.drop(1)
                                ?.substringBefore(",")
                                ?.toIntOrNull()
                        }
                    oldLine = starts.firstOrNull()
                    newLine = starts.getOrNull(1)
                    UnifiedDiffRow(index, UnifiedDiffRowKind.Hunk, null, null, line)
                }
                line.startsWith("+") && !line.startsWith("+++") ->
                    UnifiedDiffRow(index, UnifiedDiffRowKind.Addition, null, newLine, line.drop(1))
                        .also { newLine = newLine?.plus(1) }
                line.startsWith("-") && !line.startsWith("---") ->
                    UnifiedDiffRow(index, UnifiedDiffRowKind.Deletion, oldLine, null, line.drop(1))
                        .also { oldLine = oldLine?.plus(1) }
                line.startsWith("\\ No newline at end of file") ->
                    UnifiedDiffRow(index, UnifiedDiffRowKind.Metadata, null, null, line)
                else ->
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
        }
    }
}

@Composable
fun OpenCodeUnifiedDiff(
    file: FileDiff,
    modifier: Modifier = Modifier,
    contextMode: UnifiedDiffContextMode = UnifiedDiffContextMode.Collapsed,
    reviewed: Boolean = false,
    selectedHunkIndex: Int? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (reviewed) 0.72f else 1f)
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column(modifier = Modifier.padding(12.dp).semantics { contentDescription = file.summary() }) {
            Text(file.path, style = CodeTextStyle)
            Text(
                "${file.status.label()}  +${file.additions}  −${file.deletions}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val rows = UnifiedDiffParser.parse(file.patch.orEmpty())
            rows
                .filterIndexed { index, row ->
                    contextMode == UnifiedDiffContextMode.Expanded ||
                        row.kind != UnifiedDiffRowKind.Context ||
                        (maxOf(0, index - 3)..minOf(rows.lastIndex, index + 3)).any {
                            rows[it].kind != UnifiedDiffRowKind.Context
                        }
                }.forEach { row ->
                    UnifiedDiffRowView(
                        row,
                        selected = row.index == selectedHunkIndex && row.kind == UnifiedDiffRowKind.Hunk,
                    )
                }
        }
    }
}

@Composable
private fun UnifiedDiffRowView(
    row: UnifiedDiffRow,
    selected: Boolean,
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
            Modifier
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
